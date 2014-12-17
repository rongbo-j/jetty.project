package org.eclipse.jetty.io;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.SelectorManager.SelectableEndPoint;
import org.eclipse.jetty.io.SelectorManager.State;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>{@link ManagedSelector} wraps a {@link Selector} simplifying non-blocking operations on channels.</p>
 * <p>{@link ManagedSelector} runs the select loop, which waits on {@link Selector#select()} until events
 * happen for registered channels. When events happen, it notifies the {@link EndPoint} associated
 * with the channel.</p>
 */
public class ManagedSelector extends AbstractLifeCycle implements Runnable, Dumpable
{
    /**
     * 
     */
    private final SelectorManager _selectorManager;
    private final AtomicReference<State> _state= new AtomicReference<>(State.PROCESSING);
    private List<Runnable> _runChanges = new ArrayList<>();
    private List<Runnable> _addChanges = new ArrayList<>();
    private final int _id;
    private Selector _selector;
    volatile Thread _thread;

    public ManagedSelector(SelectorManager selectorManager, int id)
    {
        _selectorManager = selectorManager;
        _id = id;
        setStopTimeout(5000);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        _selector = newSelector();
        _state.set(State.PROCESSING);
    }

    protected Selector newSelector() throws IOException
    {
        return Selector.open();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (SelectorManager.LOG.isDebugEnabled())
            SelectorManager.LOG.debug("Stopping {}", this);
        Stop stop = new Stop();
        submit(stop);
        stop.await(getStopTimeout());
        if (SelectorManager.LOG.isDebugEnabled())
            SelectorManager.LOG.debug("Stopped {}", this);
    }

    
    /**
     * <p>Submits a change to be executed in the selector thread.</p>
     * <p>Changes may be submitted from any thread, and the selector thread woken up
     * (if necessary) to execute the change.</p>
     *
     * @param change the change to submit
     */
    public void submit(Runnable change)
    {
        // This method may be called from the selector thread, and therefore
        // we could directly run the change without queueing, but this may
        // lead to stack overflows on a busy server, so we always offer the
        // change to the queue and process the state.

        if (SelectorManager.LOG.isDebugEnabled())
            SelectorManager.LOG.debug("Queued change {}", change);

        out: while (true)
        {
            State state=_state.get();
            switch (state)
            {
                case PROCESSING:
                    // If we are processing
                    if (!_state.compareAndSet(State.PROCESSING, State.LOCKED))
                        continue;
                    // we can just lock and add the change
                    _addChanges.add(change);
                    _state.set(State.PROCESSING);
                    break out;
                    
                case SELECTING:
                    // If we are processing
                    if (!_state.compareAndSet(State.SELECTING, State.LOCKED))
                        continue;
                    // we must lock, add the change and wakeup the selector
                    _addChanges.add(change);
                    _selector.wakeup();
                    // we move to processing state now, because the selector will
                    // not block and this avoids extra calls to wakeup()
                    _state.set(State.PROCESSING);
                    break out;
                    
                case LOCKED:
                    Thread.yield();
                    continue;
                    
                default:
                    throw new IllegalStateException();    
            }
        }
    }

    protected void runChange(Runnable change)
    {
        try
        {
            if (SelectorManager.LOG.isDebugEnabled())
                SelectorManager.LOG.debug("Running change {}", change);
            change.run();
        }
        catch (Throwable x)
        {
            SelectorManager.LOG.debug("Could not run change " + change, x);
        }
    }

    @Override
    public void run()
    {
        _thread = Thread.currentThread();
        String name = _thread.getName();
        int priority = _thread.getPriority();
        try
        {
            if (_selectorManager._priorityDelta != 0)
                _thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority + _selectorManager._priorityDelta)));

            _thread.setName(String.format("%s-selector-%s@%h/%d", name, _selectorManager.getClass().getSimpleName(), _selectorManager.hashCode(), _id));
            if (SelectorManager.LOG.isDebugEnabled())
                SelectorManager.LOG.debug("Starting {} on {}", _thread, this);
            while (isRunning())
                select();
            while (isStopping())
                select();
        }
        finally
        {
            if (SelectorManager.LOG.isDebugEnabled())
                SelectorManager.LOG.debug("Stopped {} on {}", _thread, this);
            _thread.setName(name);
            if (_selectorManager._priorityDelta != 0)
                _thread.setPriority(priority);
        }
    }

    /**
     * <p>Process changes and waits on {@link Selector#select()}.</p>
     *
     * @see #submit(Runnable)
     */
    public void select()
    {
        boolean debug = SelectorManager.LOG.isDebugEnabled();
        try
        {

            // Run the changes, and only exit if we ran all changes
            loop: while(true)
            {
                State state=_state.get();
                switch (state)
                {
                    case PROCESSING:    
                        // We can loop on _runChanges list without lock, because only access here.
                        int size = _runChanges.size();
                        for (int i=0;i<size;i++)
                            runChange(_runChanges.get(i));
                        _runChanges.clear();
                        

                        // Do we have new changes?
                        if (!_state.compareAndSet(state, State.LOCKED))
                            continue;
                        if (_addChanges.isEmpty())
                        {
                            // No, so lets go selecting
                            _state.set(State.SELECTING);
                            break loop;
                        }
                            
                        // We have changes, so switch add/run lists and go keep processing
                        List<Runnable> tmp=_runChanges;
                        _runChanges=_addChanges;
                        _addChanges=tmp;
                        _state.set(State.PROCESSING);
                        continue;

                        
                    case LOCKED:
                        Thread.yield();
                        continue;
                        
                    default:
                        throw new IllegalStateException();    
                }
            }
        
            // Do the selecting!
            int selected;
            if (debug)
            {
                SelectorManager.LOG.debug("Selector loop waiting on select");
                selected = _selector.select();
                SelectorManager.LOG.debug("Selector loop woken up from select, {}/{} selected", selected, _selector.keys().size());
            }
            else
                selected = _selector.select();

            // We have finished selecting.  This while loop could probably be replaced with just 
            // _state.compareAndSet(State.SELECTING, State.PROCESSING)
            // since if state is locked by submit, the resulting state will be processing anyway.
            // but let's be thorough and do the full loop.
            out: while(true)
            {
                switch (_state.get())
                {
                    case SELECTING:
                        // we were still in selecting state, so probably have
                        // selected a key, so goto processing state to handle
                        if (_state.compareAndSet(State.SELECTING, State.PROCESSING))
                            continue;
                        break out;
                    case PROCESSING:
                        // we were already in processing, so were woken up by a change being
                        // submitted, so no state change needed - lets just process
                        break out;
                    case LOCKED:
                        // A change is currently being submitted.  This does not matter
                        // here so much, but we will spin anyway so we don't race it later nor
                        // overwrite it's state change.
                        Thread.yield();
                        continue;
                    default:
                        throw new IllegalStateException();    
                }
            }

            // Process any selected keys
            Set<SelectionKey> selectedKeys = _selector.selectedKeys();
            for (SelectionKey key : selectedKeys)
            {
                if (key.isValid())
                {
                    processKey(key);
                }
                else
                {
                    if (debug)
                        SelectorManager.LOG.debug("Selector loop ignoring invalid key for channel {}", key.channel());
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                        ((EndPoint)attachment).close();
                }
            }
            
            // Allow any dispatched tasks to run.
            Thread.yield();

            // Update the keys.  This is done separately to calling processKey, so that any momentary changes
            // to the key state do not have to be submitted, as they are frequently reverted by the dispatched
            // handling threads.
            for (SelectionKey key : selectedKeys)
            {
                if (key.isValid())
                    updateKey(key);
            }
            
            selectedKeys.clear();
        }
        catch (Throwable x)
        {
            if (isRunning())
                SelectorManager.LOG.warn(x);
            else
                SelectorManager.LOG.ignore(x);
        }
    }

    private void processKey(SelectionKey key)
    {
        Object attachment = key.attachment();
        try
        {
            if (attachment instanceof SelectableEndPoint)
            {
                ((SelectableEndPoint)attachment).onSelected();
            }
            else if (key.isConnectable())
            {
                processConnect(key, (Connect)attachment);
            }
            else if (key.isAcceptable())
            {
                processAccept(key);
            }
            else
            {
                throw new IllegalStateException();
            }
        }
        catch (CancelledKeyException x)
        {
            SelectorManager.LOG.debug("Ignoring cancelled key for channel {}", key.channel());
            if (attachment instanceof EndPoint)
                closeNoExceptions((EndPoint)attachment);
        }
        catch (Throwable x)
        {
            SelectorManager.LOG.warn("Could not process key for channel " + key.channel(), x);
            if (attachment instanceof EndPoint)
                closeNoExceptions((EndPoint)attachment);
        }
    }

    private void updateKey(SelectionKey key)
    {
        Object attachment = key.attachment();
        if (attachment instanceof SelectableEndPoint)
            ((SelectableEndPoint)attachment).updateKey();
    }

    private void processConnect(SelectionKey key, Connect connect)
    {
        SocketChannel channel = (SocketChannel)key.channel();
        try
        {
            key.attach(connect.attachment);
            boolean connected = _selectorManager.finishConnect(channel);
            if (connected)
            {
                connect.timeout.cancel();
                key.interestOps(0);
                EndPoint endpoint = createEndPoint(channel, key);
                key.attach(endpoint);
            }
            else
            {
                throw new ConnectException();
            }
        }
        catch (Throwable x)
        {
            connect.failed(x);
        }
    }
    
    private void processAccept(SelectionKey key)
    {
        ServerSocketChannel server = (ServerSocketChannel)key.channel();
        SocketChannel channel = null;
        try
        {
            while ((channel = server.accept()) != null)
            {
                _selectorManager.accepted(channel);
            }
        }
        catch (Throwable x)
        {
            closeNoExceptions(channel);
            SelectorManager.LOG.warn("Accept failed for channel " + channel, x);
        }
    }

    private void closeNoExceptions(Closeable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (Throwable x)
        {
            SelectorManager.LOG.ignore(x);
        }
    }

    public boolean isSelectorThread()
    {
        return Thread.currentThread() == _thread;
    }

    private EndPoint createEndPoint(SocketChannel channel, SelectionKey selectionKey) throws IOException
    {
        EndPoint endPoint = _selectorManager.newEndPoint(channel, this, selectionKey);
        _selectorManager.endPointOpened(endPoint);
        Connection connection = _selectorManager.newConnection(channel, endPoint, selectionKey.attachment());
        endPoint.setConnection(connection);
        _selectorManager.connectionOpened(connection);
        if (SelectorManager.LOG.isDebugEnabled())
            SelectorManager.LOG.debug("Created {}", endPoint);
        return endPoint;
    }

    public void destroyEndPoint(EndPoint endPoint)
    {
        if (SelectorManager.LOG.isDebugEnabled())
            SelectorManager.LOG.debug("Destroyed {}", endPoint);
        Connection connection = endPoint.getConnection();
        if (connection != null)
            _selectorManager.connectionClosed(connection);
        _selectorManager.endPointClosed(endPoint);
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append(" id=").append(String.valueOf(_id)).append("\n");

        Thread selecting = _thread;

        Object where = "not selecting";
        StackTraceElement[] trace = selecting == null ? null : selecting.getStackTrace();
        if (trace != null)
        {
            for (StackTraceElement t : trace)
                if (t.getClassName().startsWith("org.eclipse.jetty."))
                {
                    where = t;
                    break;
                }
        }

        Selector selector = _selector;
        if (selector != null && selector.isOpen())
        {
            final ArrayList<Object> dump = new ArrayList<>(selector.keys().size() * 2);
            dump.add(where);

            DumpKeys dumpKeys = new DumpKeys(dump);
            submit(dumpKeys);
            dumpKeys.await(5, TimeUnit.SECONDS);

            ContainerLifeCycle.dump(out, indent, dump);
        }
    }

    public void dumpKeysState(List<Object> dumps)
    {
        Selector selector = _selector;
        Set<SelectionKey> keys = selector.keys();
        dumps.add(selector + " keys=" + keys.size());
        for (SelectionKey key : keys)
        {
            if (key.isValid())
                dumps.add(key.attachment() + " iOps=" + key.interestOps() + " rOps=" + key.readyOps());
            else
                dumps.add(key.attachment() + " iOps=-1 rOps=-1");
        }
    }

    @Override
    public String toString()
    {
        Selector selector = _selector;
        return String.format("%s keys=%d selected=%d",
                super.toString(),
                selector != null && selector.isOpen() ? selector.keys().size() : -1,
                selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1);
    }

    private class DumpKeys implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<Object> _dumps;

        private DumpKeys(List<Object> dumps)
        {
            this._dumps = dumps;
        }

        @Override
        public void run()
        {
            dumpKeysState(_dumps);
            latch.countDown();
        }

        public boolean await(long timeout, TimeUnit unit)
        {
            try
            {
                return latch.await(timeout, unit);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    class Acceptor implements Runnable
    {
        private final ServerSocketChannel _channel;

        public Acceptor(ServerSocketChannel channel)
        {
            this._channel = channel;
        }

        @Override
        public void run()
        {
            try
            {
                SelectionKey key = _channel.register(_selector, SelectionKey.OP_ACCEPT, null);
                if (SelectorManager.LOG.isDebugEnabled())
                    SelectorManager.LOG.debug("{} acceptor={}", this, key);
            }
            catch (Throwable x)
            {
                closeNoExceptions(_channel);
                SelectorManager.LOG.warn(x);
            }
        }
    }

    class Accept implements Runnable
    {
        private final SocketChannel channel;
        private final Object attachment;

        Accept(SocketChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
        }

        @Override
        public void run()
        {
            try
            {
                SelectionKey key = channel.register(_selector, 0, attachment);
                EndPoint endpoint = createEndPoint(channel, key);
                key.attach(endpoint);
            }
            catch (Throwable x)
            {
                closeNoExceptions(channel);
                SelectorManager.LOG.debug(x);
            }
        }
    }

    class Connect implements Runnable
    {
        private final AtomicBoolean failed = new AtomicBoolean();
        private final SocketChannel channel;
        private final Object attachment;
        private final Scheduler.Task timeout;

        Connect(SocketChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
            this.timeout = ManagedSelector.this._selectorManager.scheduler.schedule(new ConnectTimeout(this), ManagedSelector.this._selectorManager.getConnectTimeout(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void run()
        {
            try
            {
                channel.register(_selector, SelectionKey.OP_CONNECT, this);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        private void failed(Throwable failure)
        {
            if (failed.compareAndSet(false, true))
            {
                timeout.cancel();
                closeNoExceptions(channel);
                ManagedSelector.this._selectorManager.connectionFailed(channel, failure, attachment);
            }
        }
    }

    private class ConnectTimeout implements Runnable
    {
        private final Connect connect;

        private ConnectTimeout(Connect connect)
        {
            this.connect = connect;
        }

        @Override
        public void run()
        {
            SocketChannel channel = connect.channel;
            if (channel.isConnectionPending())
            {
                if (SelectorManager.LOG.isDebugEnabled())
                    SelectorManager.LOG.debug("Channel {} timed out while connecting, closing it", channel);
                connect.failed(new SocketTimeoutException());
            }
        }
    }

    private class Stop implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void run()
        {
            try
            {
                for (SelectionKey key : _selector.keys())
                {
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                    {
                        EndPointCloser closer = new EndPointCloser((EndPoint)attachment);
                        ManagedSelector.this._selectorManager.execute(closer);
                        // We are closing the SelectorManager, so we want to block the
                        // selector thread here until we have closed all EndPoints.
                        // This is different than calling close() directly, because close()
                        // can wait forever, while here we are limited by the stop timeout.
                        closer.await(getStopTimeout());
                    }
                }

                closeNoExceptions(_selector);
            }
            finally
            {
                latch.countDown();
            }
        }

        public boolean await(long timeout)
        {
            try
            {
                return latch.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    private class EndPointCloser implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final EndPoint endPoint;

        private EndPointCloser(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public void run()
        {
            try
            {
                closeNoExceptions(endPoint.getConnection());
            }
            finally
            {
                latch.countDown();
            }
        }

        private boolean await(long timeout)
        {
            try
            {
                return latch.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }
}