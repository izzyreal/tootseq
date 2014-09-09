// Copyright (C) 2009, 2010, 2014 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import java.util.List;
import java.util.Observable;

import javax.sound.midi.MidiMessage;

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
 * 
 * Note that this class is a composition of iterators, it can only be used by one client
 * at a time.
 * 
 * @author st
 * 
 */
public abstract class Source extends Observable
{
	/**
	 * Note that the first Track in the List should contain those events
	 * which are restricted to the first track of a type 1 standard midi file. e.g. Tempo
	 * @return the List of EventSources
	 */
	public abstract List<Track> getTracks();

	/**
	 * @return the name of this source 
	 */
	public abstract String getName();
	
	/**
	 * @return the resolution in ticks per quarter note
	 */
	public abstract int getResolution();
	
	/**
	 * Should only be called by the client, the client will behave 
	 * incorrectly if anything else calls it.
	 */
	public abstract void returnToZero();
	
	/**
	 * Should only be called by the client.
	 * This method is called synchronously by the client, each time before it obtains
	 * the List of Tracks. It is the only time the implementor is allowed to
	 * mutate the List of Tracks.
	 * Failure to mutate the underlying List only in this method will likely result
	 * in ConcurrentModificationExceptions being thrown.
	 * @param currentTick the tick the client is currently at, useful for recording
	 * @return a RepositionCommand or null
	 */
	public RepositionCommand sync(long currentTick) {
		return null; 
	}
	
	/**
	 * An implementation may override this null implementation to send
	 * MidiMessages for MTC to relevant places.
	 * @param msg
	 */
	public void receiveMTC(MidiMessage msg) {
	}
	
	public void stop() {
	    for ( Track trk : getTracks() ) {
	        trk.off(true);
	    }           
	}
	
	/**
	 * An iterator of arbitrary tick-based events which is able to play
	 * events at the correct time and control the event output in other ways.
	 */
	public interface Track
	{
		/**
		 * Return the next event tick without changing iterator position.
		 * @return the next tick or LONG_MAX if none
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
	 * A way of repositioning the client in real-time requiring both time
	 * in milliseconds and tick be provided.
	 * An instance of this class may be synchronously returned to the client
	 * via sync().
	 * @author st
	 */
	public class RepositionCommand
	{
		private long millis;
		private long tick;
		
		public RepositionCommand(long millis, long tick) {
			this.millis = millis;
			this.tick = tick;
		}
		
		public long getMillis() { return millis; }
		
		public long getTick() { return tick; }
	}
}
