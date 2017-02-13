/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package lockstep;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import lockstep.messages.simulation.FrameACK;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.log4j.Logger;


/**
 * This frame queue supports out of order and simultaneous insertion, but only 
 * in order single extraction.
 *
 * It is thread safe, as producer and consumer access different locations of this
 * data structure.
 * 
 * @author Raff
 */

class ExecutionFrameQueue
{
    /**
     * Internally it behaves as an infinite array of which, at any time, only
     * indexes in [baseFrameNumber, baseFrameNumber + bufferSize - 1] can be
     * accessed.
     */
    
    int bufferSize;
    int bufferHead;
    int baseFrameNumber;
    FrameInput[] frameBuffer;

    int lastInOrder;
    ConcurrentSkipListSet<Integer> selectiveACKsSet;

    CyclicCountDownLatch cyclicExecutionLatch;
    
    private static final Logger LOG = Logger.getLogger(ExecutionFrameQueue.class.getName());
    private final int hostID;
    
    /**
     * Creates a new ExecutionFrameQueue
     * @param bufferSize Size of the internal buffer. It's important to
     * dimension this large enough to store the received frames without forcing
     * retransmissions
     * @param initialFrameNumber First frame's number. Must be the same for all 
     * the clients using the protocol
     */
    public ExecutionFrameQueue(int bufferSize, int initialFrameNumber, int hostID, CyclicCountDownLatch cyclicExecutionLatch)
    {
        this.bufferSize = bufferSize;
        this.frameBuffer = new FrameInput[bufferSize];
        this.bufferHead = 0;
        this.baseFrameNumber = initialFrameNumber;
        this.lastInOrder = initialFrameNumber - 1;
        this.selectiveACKsSet = new ConcurrentSkipListSet<>();
        this.hostID = hostID;
        this.cyclicExecutionLatch = cyclicExecutionLatch;
    }
    
    /**
     * Extracts the next frame input only if it's in order. 
     * This method will change the queue, extracting the head, only if it's present.
     * @return the next in order frame input, or null if not present. 
     */
    public FrameInput pop()
    {
        FrameInput nextInput = this.frameBuffer[this.bufferHead];
        if( nextInput != null )
        {
            this.frameBuffer[this.bufferHead] = null;
            this.bufferHead = (this.bufferHead + 1) % this.bufferSize;
            
            if(this.frameBuffer[this.bufferHead] != null)
                cyclicExecutionLatch.countDown();
        }
        else
        {
            LOG.debug("ExecutionFrameQueue " + hostID + ": FrameInput missing for current frame");
        }
        return nextInput;
    }
    
    /**
     * Shows the head of the buffer. This method won't modify the queue.
     * @return next in order frame input, or null if not present.
     */
    public FrameInput head()
    {
        return this.frameBuffer[this.bufferHead];
    }
    
    /**
     * Inserts all the inputs passed, provided they're in the interval currently
     * accepted. If a FrameInput it's out of the interval it's discarded. 
     * @param inputs the FrameInputs to insert
     * @return the FrameACK to send back
     */
    public FrameACK push(FrameInput[] inputs)
    {
        for(FrameInput input : inputs)
        {
            boolean toSelectivelyACK = _push(input);
            if(toSelectivelyACK)
                selectiveACKsSet.add(input.frameNumber);
        }
        
        return new FrameACK(lastInOrder, _getSelectiveACKs());
    }
        
    /**
     * Inserts the input passed, provided it is in the interval currently
     * accepted. Otherwise it's discarded.
     * 
     * @param input the FrameInput to insert
     * @return the FrameACK to send back
     */
    public FrameACK push(FrameInput input)
    {
        boolean toSelectivelyACK = _push(input);
        if(toSelectivelyACK)
            this.selectiveACKsSet.add(input.frameNumber);
        
        return new FrameACK(lastInOrder, _getSelectiveACKs());
    }
        
    /**
     * Internal method to push a single input into the queue.
     * 
     * @param input the input to push into the queue
     * @return A boolean indicating whether the input should be selectively ACKed
     */
    private boolean _push(FrameInput input)
    {
        if(input.frameNumber >= this.baseFrameNumber && input.frameNumber <= this.baseFrameNumber + this.bufferSize - 1)
        {
            int bufferIndex = (input.frameNumber - this.baseFrameNumber + this.bufferHead) % this.bufferSize;
            if(this.frameBuffer[bufferIndex] == null)
                this.frameBuffer[bufferIndex] = input;
            
            if(input.frameNumber == this.lastInOrder + 1)
            {
                if(bufferIndex == bufferHead)
                    this.cyclicExecutionLatch.countDown();

                this.lastInOrder++;
                while(!this.selectiveACKsSet.isEmpty() && this.selectiveACKsSet.first() == this.lastInOrder + 1)
                {
                    this.lastInOrder++;
                    this.selectiveACKsSet.remove(this.selectiveACKsSet.first());
                }
                return false;
            }
            else
                return true;
        }
        else
        {
            LOG.debug("Frame arrived out of buffer bound");
            return false;
        }
    }
        
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
}
