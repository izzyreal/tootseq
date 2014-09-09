// Copyright (C) 2009 Steve Taylor.
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
 * There are a few edge cases in iteration.
 * 
 * Assume we have notes X then Y and X has already been consumed such that
 * peek() and next() would both return Y. At some point peek() will return Y and
 * its tick will indicate that it is ready to play so we would proceed to obtain
 * it with next(). If inbetween peek() and next() the model is altered such that
 * note Z is inserted between X and Y there are two eventualities. next() will
 * now return Z. It may be too late to play in which case it should be ignored
 * but it may be that it should play in which case it will be played and peek()
 * and next() would subsequently obtain note Y which next() was originally
 * expecting. So in this case an apparent race condition with note insertion
 * near the current iterator position is handled properly.
 * 
 * Again, assume we have notes X then Y then Z and X has already been consumed such that
 * peek() and next() would both return Y. At some point peek() will return Y and
 * its tick will indicate that it is ready to play so we would proceed to obtain
 * it with next(). If inbetween peek() and next() the model is altered such that
 * Y is deleted next() will return Z. It may be that Z is too early to be played
 * in which case next() has consumed it too early and Z would not subsequently
 * be played at the appropriate time. In this case the race condition with note deletion
 * near the current iterator position is not handled properly and notes may be lost.
 * It should be noted that this race condition typically only occurs if a note is deleted
 * at exactly the millisecond it becomes playable, it is unlikely but possible.
 * 
 * Assume we have notes X then Y and X has already been consumed such that
 * peek() and next() would both return Y. Assume X is deleted. This may require
 * the iterator to reposition itself such that next() still returns Y.
 * 
 * In general an iterator should not maintain position by referencing the next note.
 * The next note may be varying as new notes are added, so it is arguably best practice to
 * maintain position by referencing the previous note.
 * 
 * Note that this class is a composition of iterators, it can only be used by one client
 * at a time.
 * 
 * @author st
 * 
 */
public abstract class Source extends Observable
{
	private boolean syncing = false;
	
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
	 * mutate the List of Tracks. Some clients of may not call sync() 
	 * because they are not prepared to accept mutations to the List of Tracks.
	 * Failure to mutate the underlying List only in this method will likely result
	 * in ConcurrentModificationExceptions being thrown.
	 * @param currentTick the tick the client is currently at, useful for recording
	 * @return a RepositionCommand or null
	 */
	public RepositionCommand sync(long currentTick) {
		syncing = true;
		return null; 
	}
	
	/**
	 * An implementation may only mutate the List and reposition the client
	 * if this method returns true. This method is provided so that an
	 * implementation explicitly knows whether these operations are supported,
	 * but these operations will simply not function if they are not supported.
	 * @return whether List mutation and client repositioning is available
	 */
	protected boolean isSyncing() { return syncing; }
	
	/**
	 * An iterator of MidiEvents.
	 * We don't implement hasNext() because in general there is no iterator
	 * termination, another MidiEvent may be created at ant time. Also, if there
	 * is a next MidiEvent we want to know what it is in order to examine its tick.
	 * MidiEvent tick values should monotonically increase even if repositioning or looping.
	 * @author st
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
		
		/*
		 * Turn this track off, turn notes off etc.
		 * With midi affect conmtroller if stop (otherwise not in mute)
		 */
		public void off(boolean stop);
		
		/**
		 * Return the name of the EventSource, which should be unique
		 * for each EventSource belonging to this MidiSource.
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
