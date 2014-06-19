package com.ociweb.jfast.stream;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ociweb.jfast.field.ByteHeap;
import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TextHeap;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.FieldReferenceOffsetManager;
import com.ociweb.jfast.primitive.PrimitiveReader;

/**
 * Specialized ring buffer for holding decoded values from a FAST stream. Ring
 * buffer has blocks written which correspond to whole messages or sequence
 * items. Within these blocks the consumer is provided random (eg. direct)
 * access capabilities.
 * 
 * 
 * 
 * @author Nathan Tippy
 * 
 * 
 * Storage:
 *  int - 1 slot
 *  long - 2 slots, high then low 
 *  text - 2 slots, index then length  (if index is negative use constant array)
 * 
 */
public final class FASTRingBuffer {

    public static class PaddedLong {
        public long value = 0, padding1, padding2, padding3, padding4;
    }
    
    public final int[] buffer;
    public final int mask;
    public final PaddedLong addPos = new PaddedLong();
    public final PaddedLong remPos = new PaddedLong();
    
    public final int maxSize;

    public final int maxCharSize;
    public final int charMask;
    public final char[] charBuffer;
    public int addCharPos = 0;

    final int maxByteSize;
    final int byteMask;
    final byte[] byteBuffer;
    int addBytePos = 0;
    
    final char[] constTextBuffer; //defined externally and never changes
    final byte[] constByteBuffer;


    final AtomicLong removeCount = new PaddedAtomicLong(); //reader reads from this position.
    public final AtomicLong addCount = new PaddedAtomicLong(); // consumer is allowed to read up to addCount
    long lastRead;
    
  //TODO: B, write templateId and dispatch instance in leading integer. If value is bad the dispatch can be reset.
    
    
    //TODO: A, use stack of offsets for each fragment until full message is completed.
    //TODO: B, first offset 0 points to the constants after the ring buffer.
    private int[] fragStack;
    
    //Need to know when the new template starts
    //each fragment size must be known and looked up
    FieldReferenceOffsetManager from;
    int[] templateStartIdx;

    public FASTRingBuffer(byte primaryBits, byte charBits, DictionaryFactory dcr, int maxFragDepth, FieldReferenceOffsetManager from, int[] templateStartIdx) {
        assert (primaryBits >= 1);       
        
        this.fragStack = new int[maxFragDepth];
        
        //single buffer size for every nested set of groups, must be set to support the largest need.
        this.maxSize = 1 << primaryBits;
        this.mask = maxSize - 1;
        
        this.buffer = new int[maxSize];      
        

        //TODO: A, use callback upon new class load to reset field offsets.

        //constant data will never change and is populated externally.
        if (null!=dcr) {
            TextHeap textHeap = dcr.charDictionary();
            if (null!=textHeap) {
                this.constTextBuffer = textHeap.rawInitAccess();            
            } else {
                this.constTextBuffer = null;
            }
            ByteHeap byteHeap = dcr.byteDictionary();
            if (null!=byteHeap) {
                this.constByteBuffer = byteHeap.rawInitAccess();            
            } else {
                this.constByteBuffer = null;
            }
        } else {
            this.constTextBuffer = null;
            this.constByteBuffer = null;
        }
                        
        //single text and byte buffers because this is where the variable length data will go.
        
        this.maxCharSize = 1 << charBits;
        this.charMask = maxCharSize - 1;
        this.charBuffer = new char[maxCharSize];

        this.maxByteSize = maxCharSize;
        this.byteMask = maxByteSize - 1;
        this.byteBuffer = new byte[maxByteSize];
        
        this.from = from;
        this.templateStartIdx = templateStartIdx;
        
    }

    //TODO: AA, must add way of selecting what field to skip writing for the consumer.
    
    /**
     * Empty and restore to original values.
     */
    public void reset() {
        addCharPos = 0;
        addPos.value = 0;
        remPos.value = 0;
        removeCount.set(0);
        addCount.set(0);
    }

    // adjust these from the offset of the biginning of the message.

    public int messageId() {
        return messageId;
    }
    
    int messageId = -1;
    int cursor=0;
    int[] seqStack = new int[10];//TODO: how deep is this?
    int seqStackHead = -1;
    final int JUMP_MASK = 0xFFFFF;
    
    public void moveNext() {
        // step forward and allow write to previous location.
        if (messageId<0) {
            //TODO: need to step over the preamble? but how?
            messageId = FASTRingBufferReader.readInt(this,  1); //TODO: how do we know this is one?
            //start new message, can not be seq or optional group or end of message.
            cursor = from.starts[messageId];
            System.err.println("*******  new start at:"+cursor);
        } else {
            
            
            
            int fragSize = from.fragDataSize[cursor];  //size of fragment in data
            int fragStep = from.fragScriptSize[cursor]; //script jump 
            //int token    = from.tokens[cursor];    //field type and operator
            
//            int lastLen = FASTRingBufferReader.readInt(this, fragSize+1); //TODO: why is this here? it is off by 2?
//            System.err.println("last len:"+lastLen+" at "+(this.removeCount.get()+(fragSize-1))+" cursor "+cursor+" fragStep "+fragStep );
            
            System.err.println("jump by frag size:"+fragSize+" from "+cursor);
            
            remPos.value = removeCount.addAndGet(fragSize);
            cursor += fragStep;

            ///TODO: add optional groups to this implementation
            ///TODO: can we remove fragJump and use token?
            
            //////////////
            ////Never call these when we jump back for loop
            //////////////
            System.err.println("cursor "+cursor);
            if (sequenceLengthDetector(fragStep)) {
                endOfMessageDetector();
            }
            
        }
        
        
    }

    
    //only called after moving foward.
    private boolean sequenceLengthDetector(int jumpSize) {
        if(cursor==0) {
            return false;
        }
        int endingToken = from.tokens[cursor-1];
        //if last token of last fragment was length then begin new sequence
        int type = TokenBuilder.extractType(endingToken);
        if (TypeMask.GroupLength == type) {
            int seqLength = FASTRingBufferReader.readInt(this, -1); //length is always at the end of the fragment.
            
            //TODO: off by 2 because 1 for token id and 1 for preamble which are not in the script!!!
            System.err.println("seq len :"+seqLength+" at "+(this.remPos.value-1));
            
            if (seqLength == 0) {
                System.err.println("******************** jump over seq");
                //do nothing and jump over the sequence
                //there is no data in the ring buffer so do not adjust position
                int fragJump = from.fragScriptSize[cursor]; //script jump  //TODO: not sure this is right whenthey are nested?
                cursor += (fragJump&JUMP_MASK);
                //done so move to the next item
                cursor++;
                return true;
            } else {
                System.err.println("new Seq at top");
                //push onto stack
                seqStack[++seqStackHead]=seqLength;
                //this is the first run so we are already positioned at the top                
            }
            return false;   
            
        }
        //if last token of last fragment was seq close then subtract and move back.
        if (TypeMask.Group==type && 0 == (endingToken & (OperatorMask.Group_Bit_Seq << TokenBuilder.SHIFT_OPER))) {
            //check top of the stack
            if (--seqStack[seqStackHead]>0) {
                System.err.println("return to top for seq");
                //return to top 
               cursor -= from.fragScriptSize[cursor-jumpSize]&JUMP_MASK;
               return false;
            } else {
                System.err.println("done with seq");
                //done
                //already positioned to continue
                seqStackHead--;
                //done so move to the next item
                cursor++;
                return true;
            }
        }                   
        return true;
    }
    
    
    private void endOfMessageDetector() {
        if (cursor>=from.tokens.length) {
            messageId = -1;
            System.err.println("eom1 "+cursor);
            return;
        }
        int token = from.tokens[cursor-1];
        //TODO: need extra flag to distinguish from closig message and clossing optional group.
        
        if (TokenBuilder.extractType(token)==TypeMask.Group && 0!=(token & OperatorMask.Group_Bit_Close)) {
            messageId = -1;
            System.err.println("eom2 "+cursor);
        }
    }


    // TODO: C, add map method which can take data from one ring buffer and
    // populate another.

    // TODO: C, Promises/Futures/Listeners as possible better fit to stream
    // processing?
    // TODO: C, look at adding reduce method in addition to filter.

    public final int availableCapacity() {
        return maxSize - (int)(addPos.value - remPos.value);
    }

    public static int peek(int[] buf, long pos, int mask) {
        return buf[mask & (int)pos];
    }

    public static long peekLong(int[] buf, long pos, int mask) {
        return (((long) buf[mask & (int)pos]) << 32) | (((long) buf[mask & (int)(pos + 1)]) & 0xFFFFFFFFl);

    }

    // TODO: Z, add consumer/Iterator to go from ring buffer to Object stream
    // TODO: Z, Map templates to methods for RMI of void methods(eg. one direction).
    // TODO: Z, add map toIterator method for consuming ring buffer by java8 streams.


    public static int writeTextToRingBuffer(int heapId, int len, TextHeap textHeap, FASTRingBuffer rbRingBuffer) {//Invoked 100's of millions of times, must be tight.
        if (len > 0) {
            final int p = rbRingBuffer.addCharPos;
            rbRingBuffer.addCharPos = TextHeap.copyToRingBuffer(heapId, rbRingBuffer.charBuffer, p, rbRingBuffer.charMask, textHeap);
            return p;
        } else {
            return 0;//should never read from here anyway so zero is safe
        }
    }
    
    public static int writeTextToRingBuffer(int len, PrimitiveReader reader, FASTRingBuffer rbRingBuffer) {//Invoked 100's of millions of times, must be tight.
        final int p = rbRingBuffer.addCharPos;
        if (len > 0) {
            
            int lenTemp = PrimitiveReader.readTextASCIIIntoRing(rbRingBuffer.charBuffer, p, rbRingBuffer.charMask, reader);
            rbRingBuffer.addCharPos+=lenTemp;// = TextHeap.copyToRingBuffer(heapId, charBuffer, p, charMask,textHeap);
        }
        return p;
    }

    public static int writeBytesToRingBuffer(int heapId, int len, ByteHeap byteHeap, FASTRingBuffer rbRingBuffer) {
        final int p = rbRingBuffer.addBytePos;
        if (len > 0) {
            rbRingBuffer.addBytePos = byteHeap.copyToRingBuffer(heapId, rbRingBuffer.byteBuffer, p, rbRingBuffer.byteMask);
        }
        return p;
    }

    // TODO: A, Callback interface for setting the offsets used by the clients, Generate list of FieldId static offsets for use by static reader based on templateId.
 

    // fragment is ready for consumption
    //Called once for every group close, even when nested
    //TODO: AA, Will want to add local cache of atomic in order to not lazy set twice because it is called for every close.
    public static final void unBlockFragment(FASTRingBuffer ringBuffer) {
            ringBuffer.addCount.lazySet(ringBuffer.addPos.value);
    }

    public void removeForward(int step) {
        remPos.value = removeCount.get() + step;
        assert (remPos.value <= addPos.value);
        System.err.println("remove forward to "+remPos.value);
        removeCount.lazySet(remPos.value);
    }
    
    public void removeForward2(long pos) {
        remPos.value = pos;
        System.err.println("reset remvoe forward2 to "+pos);
        removeCount.lazySet(pos);
    }

    //TODO: A: finish the field lookup so the constants need not be written to the loop! 
    //TODO: B: build custom add value for long and decimals to avoid second ref out to pos.value
    public static void addValue(int[] rbB, int rbMask, PaddedLong pos, int value) {
        long p = pos.value;
        rbB[rbMask & (int)p] = value;
        pos.value = p+1;
    }
    
    public static void dump(FASTRingBuffer rb) {
                       
        // move the removePosition up to the addPosition
         new Exception("WARNING THIS IS NO LONGER COMPATIBLE WITH PUMP CALLS").printStackTrace();
        rb.removeCount.lazySet(rb.remPos.value = rb.addPos.value);
    }

    // this is for fast direct WRITE TO target
    public void readChars(int idx, char[] target, int targetIdx, TextHeap textHeap) {
        int ref1 = buffer[(int)(mask & (remPos.value + idx))];
        if (ref1 < 0) {
            textHeap.get(ref1, target, targetIdx);
        } else {
            int len = buffer[(int)(mask & (remPos.value + idx + 1))];
            // copy into target but may need to loop from text buffer
            while (--len >= 0) {
                target[targetIdx + len] = charBuffer[(ref1 + len) & charMask];
            }
        }
    }

    // WARNING: consumer of these may need to loop around end of buffer !!
    // these are needed for fast direct READ FROM here
    public int readRingCharPos(int fieldPos) {
        // constant from heap or dynamic from char ringBuffer
        int ref1 = buffer[(int)(mask & (remPos.value + fieldPos))];
        return ref1 < 0 ? ref1&0x7FFFFFFF : ref1;
    }

    public char[] readRingCharBuffer(int fieldPos) {
        // constant from heap or dynamic from char ringBuffer
        return buffer[(int)(mask & (remPos.value + fieldPos))] < 0 ? constTextBuffer : this.charBuffer;
    }

    public int readRingCharMask() {
        return charMask;
    }



    public boolean hasContent() {
        return addPos.value > remPos.value;
    }

    public int contentRemaining() {
        return (int)(addPos.value - remPos.value);
    }

    public static long readUpToPos(FASTRingBuffer rb) {
        Thread.yield();//let the writer update the count if possible
        return rb.addCount.longValue();
    }



}
