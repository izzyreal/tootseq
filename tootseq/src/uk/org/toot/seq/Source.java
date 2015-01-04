// Copyright (C) 2009, 2010, 2014 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import java.util.List;
import java.util.Observable;

/**
 * A Source is a composite event iterator. This is the contract required
 * by a Sequencer to be able to use arbitrary track based representations
 * of music. Such representations may be edited whilst being played. In general
 * the representation need not be known a priori, it could even be generated in
 * real-time. An implementation of this class can be properly decoupled from the
 * underlying representation. The implementation of this class should know about
 * its underlying representation, that representation should not know about this
 * implementation. Clients of this class need not know about any such
 * representation or any specific implementation of this class.
 * 
 * Note that this class is a composition of iterators, it can only be used by one client
 * at a time.
 * 
 * @author st
 * 
 */
public abstract class Source extends Observable
{
    private SynchronousControl control;
    
    /*
     * set this in subclass
     */
    protected String name;
    
	/**
	 * @return the name of this Source 
	 */
	public String getName() {
	    return name;
	}
	
	/**
	 * @return the resolution in ticks per quarter note
	 */
	public abstract int getResolution();
	
    /**
     * @return the List of Tracks
     */
    public abstract List<Track> getTracks();

    /**
     * Called by the Sequencer once when it begins using this source to
     * privately provide us with its synchronous control interface
     * @param ctl
     */
    protected void control(SynchronousControl ctl) {
        control = ctl;
    }
    
	/**
	 * Should only be called by the Sequencer, the Sequencer will behave 
	 * incorrectly if anything else calls it.
	 */
	protected abstract void returnToZero();
	
	/**
	 * Should only be called by the Sequencer.
	 * This method is called synchronously by the client, each time before it obtains
	 * the List of Tracks. It is the only time the implementor is allowed to
	 * mutate the List of Tracks.
	 * Failure to mutate the underlying List only in this method will likely result
	 * in ConcurrentModificationExceptions being thrown.
	 * If the currentTick is outside the range to be played the implementation
	 * should call reposition() to reposition the client at the next tick to be played.
	 * @param currentTick the tick the client is currently at, useful for recording
	 * @return the tick to reposition to or -1
	 */
	protected long sync(long currentTick) {
	    return -1L;
	}
	
    /**
     * Should only be called by the Sequencer.
     * Play events as they become due.
     * @param targetTick the tick to play until.
     */
    protected void playToTick(long targetTick) {
        for ( Track trk : getTracks() ) {
            while ( trk.getNextTick() <= targetTick ) {
                trk.playNext();
            }
        }
    }

    /**
     * Should only be called by the Sequencer.
     * Turn off track outputs on a stop condition
     */
 	protected void stopped() {
	    for ( Track trk : getTracks() ) {
	        trk.off(true);
	    }           
	}
	
 	/**
 	 * Should be called as a result of a call to playToTick()
 	 * Typically when the Track representing the tempo map is 'played' and tempo
 	 * events are reached.
 	 * @param bpm
 	 */
 	protected void setBpm(float bpm) {
 	    if ( control != null ) control.setBpm(bpm);
 	}
 	
	/**
	 * An iterator of arbitrary tick-based events which is able to play
	 * events at the correct time and control the event output in other ways.
	 */
	public interface Track
	{
	    public final static long MAX_TICK = Long.MAX_VALUE;
	    
		/**
		 * Return the next event tick without changing iterator position.
		 * @return the next tick or MAX_TICK if none
		 */
		public long getNextTick();
		
		/**
		 * Play the next event and increment iterator position.
		 */
		public void playNext();
		
		/**
		 * Turn this track off, turn notes off etc.
		 * With Midi, reset controllers if stop (otherwise not in mute)
		 */
		public void off(boolean stop);
		
		/**
		 * Return our name, which should be unique
		 * for each Track belonging to this Source.
		 * @return our name
		 */
		public String getName();
	}
	
	/**
	 * The interface used to call back to the Sequencer synchronously with
	 * its real time thread.
	 */
	protected interface SynchronousControl
	{
	    public void setBpm(float bpm);
	}
}
