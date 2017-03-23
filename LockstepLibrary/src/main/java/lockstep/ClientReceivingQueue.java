/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package lockstep;

import java.io.Serializable;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import lockstep.messages.simulation.FrameACK;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.log4j.Logger;


/**
 * This frame queue supports out of order and simultaneous insertion, but only 
 * in order single extraction. A semaphore is released when the next frame
 * input is available.
 * 
 * It is thread safe.
 */

class ClientReceivingQueue<Command extends Serializable> implements ReceivingQueue
{
    AtomicInteger nextFrame;
    
    volatile ConcurrentSkipListMap<Integer, Command> commandBuffer;
    volatile ConcurrentSkipListSet<Integer> selectiveACKsSet;
    volatile AtomicInteger lastInOrder;

    volatile Semaphore executionSemaphore;
    
    private static final Logger LOG = Logger.getLogger(ClientReceivingQueue.class.getName());
    private final int senderID;
    
    /**
     * Constructor.
     * 
     * @param initialFrameNumber First frame's number. Must be the same for all 
     * the hosts using the protocol
     * 
     * @param senderID ID of the client whose frames are collected in this queue
     * 
     * @param clientExecutionSemaphore semaphore used by to signal the client of
     * the availability of the next frame input. The client awaits that all the 
     * queues are ready before collecting the next frame inputs
     */
    public ClientReceivingQueue(int initialFrameNumber, int senderID, Semaphore clientExecutionSemaphore)
    {
        this.commandBuffer = new ConcurrentSkipListMap<>();
        this.nextFrame = new AtomicInteger(initialFrameNumber);
        this.lastInOrder = new AtomicInteger(initialFrameNumber - 1);
        this.selectiveACKsSet = new ConcurrentSkipListSet<>();
        this.senderID = senderID;
        this.executionSemaphore = clientExecutionSemaphore;
        
        LOG.debug("BufferHead initialized at " + initialFrameNumber);
    }
    
    /**
     * Extracts the next frame input only if it's in order. 
     * This method will change the queue, extracting the head, only if it's 
     * present.
     * 
     * @return the next in order frame input, or null if not present. 
     */
    public FrameInput<Command> pop()
    {
        Command nextCommand = this.commandBuffer.get(nextFrame.get());
        int frame = nextFrame.get();
        FrameInput frameInput = null;
        if( nextCommand != null )
        {
            frameInput = new FrameInput(frame, nextCommand);
            nextFrame.incrementAndGet();
            for(Integer key : commandBuffer.headMap(nextFrame.get()).keySet())
                commandBuffer.remove(key);
            
            if(commandBuffer.get(nextFrame.get()) != null)
            {
                //LOG.debug("Countdown to " + ( executionSemaphore.getCount() - 1) + "made by " + senderID);
                executionSemaphore.release();
            }
        }
        else
        {
            LOG.debug("ExecutionFrameQueue " + senderID + ": FrameInput missing for current frame");
        }
        return frameInput;
    }
    
    /**
     * Shows the head of the buffer. This method won't modify the queue.
     * 
     * @return next in order frame input, or null if not present.
     */
    public FrameInput<Command> head()
    {
        return new FrameInput(nextFrame.get(), commandBuffer.get(nextFrame.get()));
    }
    
    /**
     * Inserts all the inputs passed. 
     * Duplicate inputs are individually discarded.
     * 
     * @param inputs the FrameInputs to insert
     * @return the FrameACK to send back
     */
    public FrameACK push(FrameInput[] inputs)
    {
        for(FrameInput input : inputs)
            _push(input);
        
        return new FrameACK(lastInOrder.get(), _getSelectiveACKs());
    }
        
    /**
     * Inserts the input passed, provided it's not a duplicate.
     * 
     * @param input the FrameInput to insert
     * @return the FrameACK to send back
     */
    public FrameACK push(FrameInput input)
    {
        _push(input);
        
        return new FrameACK(lastInOrder.get(), _getSelectiveACKs());
    }
        
    /**
     * Internal method to push a single input into the queue.
     * It checks if the input is not a duplicate before insertion, and updates
     * ACK data after insertion.
     * 
     * @param input the input to push into the queue
     */
    private void _push(FrameInput<Command> input)
    {
        try
        {
            if( input.getFrameNumber() > lastInOrder.get() && this.commandBuffer.putIfAbsent(input.getFrameNumber(), input.getCommand()) == null)
            {
                if(input.getFrameNumber() == this.lastInOrder.get() + 1)
                {
                    lastInOrder.incrementAndGet();
                    
                    //if(!selectiveACKsSet.isEmpty())
                        //System.out.println("" + selectiveACKsSet.first() + " " + lastInOrder.get() + 1);

                    
                    while(!this.selectiveACKsSet.isEmpty() && this.selectiveACKsSet.first() == this.lastInOrder.get() + 1)
                    {
                        //System.out.println("Entrato");
                        this.lastInOrder.incrementAndGet();
                        this.selectiveACKsSet.removeAll(this.selectiveACKsSet.headSet(lastInOrder.get(), true));
                    }
                    
                    if(input.getFrameNumber() == this.nextFrame.get())
                    {
                        //LOG.debug("Countdown to " + ( executionSemaphore.getCount() - 1) + " made by " + senderID);
                        executionSemaphore.release();
                    }
                }
                else
                {
                    this.selectiveACKsSet.add(input.getFrameNumber());
                }
            }
            else
            {
                LOG.debug("Duplicate frame arrived");
            }
        }
        catch(NullPointerException e)
        {
            LOG.debug("SEGFAULT for " + senderID);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Extract an int array containing the selective ACKs
     * 
     * @return the int array of selective ACKs
     */
    
    private int[] _getSelectiveACKs()
    {
        Integer[] selectiveACKsIntegerArray = this.selectiveACKsSet.toArray(new Integer[0]);
        if(selectiveACKsIntegerArray.length > 0)
        {
            int[] selectiveACKs = ArrayUtils.toPrimitive(selectiveACKsIntegerArray);
            return selectiveACKs;
        }
        else
            return null;
    }
    
    @Override
    public String toString()
    {
        String string = new String();
        
        string += "ExecutionFrameQueue[" + senderID + "] = {";
        for(Entry<Integer, Command> entry : this.commandBuffer.entrySet())
        {
            string += " " + entry.getKey();
        }
        string += " } nextFrame = " + nextFrame.get() + " lastInOrder " + lastInOrder.get();
                
        return string;
    }
}
