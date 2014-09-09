// Copyright (C) 2009 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import java.util.List;
import java.util.Observable;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;

import static uk.org.toot.midi.message.MetaMsg.*;

/**
 * MidiPlayer plays MIDI from MidiSources in real-time. It is the real-time part
 * of a 'sequencer'. It cannot support controller chasing, repositioning while
 * running, or looping. It cannot easily support mute/solo because the List of
 * EventSources may be dynamic. These operations must be provided by individual
 * MidiSource implementations as appropriate.
 * 
 * Effectively we solve the law of motion: distance = velocity * time.
 * 
 * Distance is measured in ticks, velocity in bpm and time is, well, time.
 * Note that this equation merely represents a constant velocity but MIDI only
 * supports instantaneous transitions between constant tempos (velocities) so
 * total distance is the accumulation of a contiguous series of these linear
 * segments.
 * 
 * Because we only have knowledge of the current linear segment and the
 * accumulation of all previous linear segments we are unable to calculate the
 * position for arbitrary times that are outside the current linear segment.
 * Fundamentally this is why we cannot reposition or loop, we can't convert an
 * arbitrary time to a position. In principle we could cache details of all
 * linear segments but this does not scale well (we can play for over 200
 * million years, with position accurately calculated to within 1 millisecond,
 * which could be a very large cache) and is arguably best performed by a
 * MidiSource which may already have such a cache in practice.
 * 
 * @author st
 * 
 */
public class Sequencer extends Observable
{
	private PlayEngine playEngine;
	private boolean running = false;
	private boolean stopOnEmpty = true;
	private float bpm;
	
	private long accumTicks;		// accumulated ticks up to current segment
	private long accumMillis;		// accumulated milliseconds up to current segment
	private long refMillis;			// wall time at start of current segment
	private long elapsedMillis; 	// elapsed time within current segment
	private float ticksPerMilli;	// velocity of current segment
	
    private Source source;

	/**
	 * A lock object, required to make the accumulation of elapsedMillis into
	 * accumMillis and the associated zeroing of elapsedMillis atomic so that
	 * getMillisecondPosition() does not return transient erroneous values under
	 * stop and tempo change conditions. The only times this lock is used on the
	 * real-time thread are when a tempo change occurs and when the thread is
	 * stopping. Lock contention will only occur if getMillisecondPosition()
	 * holds the lock at that exact time and this is very unlikely given that it
	 * merely adds two longs.
	 */
	private Object milliLock = new Object();

	public void setMidiSource(Source source) {
		if ( running ) {
			throw new IllegalStateException("Can't set MidiSource while playing");
		}
        if ( source == null ) {
            throw new IllegalArgumentException("MidiSource can't be null");
        }
        this.source = source;
        source.returnToZero(); // just in case it isn't
		init();
		source.sync(0); // quickly inform source we support syncing
		notesOff();
	}

	/**
	 * Start playing
	 */
	public void play() {
		if ( source == null ) {
			throw new IllegalStateException("MidiSource is null");
		}
		if ( running ) return;
		setRunning(true);
		playEngine = new PlayEngine();
	}
	
	/**
	 * Commence stopping.
	 */
	public void stop() {
		if ( !running ) return;
		playEngine.stop();
	}
	
	/**
	 * As if setMidiSource() had been called again.
	 */
	public void returnToZero() {
		if ( source == null ) {
			throw new IllegalStateException("MidiSource is null");
		}
		// to avoid synchronisation issues
		if ( running ) {
			throw new IllegalStateException("Can't returnToZero while playing");
		}
		source.returnToZero();
		init();
	}
	
	/**
	 * Return whether we're currently playing.
	 * Note that observers are notified immediately subsequent to transitions.
	 * @return true if playing (or stopping), false if stopped
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Get the current tick position.
	 * @return the tick position in the MidiSource
	 */
	public long getTickPosition() {
		return getCurrentTimeTicks();
	}
	
	/**
	 * Get the current millisecond position
	 * @return the millisecond position in the MidiSource
	 */
	public long getMillisecondPosition() {
		synchronized ( milliLock ) {
			return accumMillis + elapsedMillis;
		}
	}
	
	/**
	 * Get the current tempo in beats per minute.
	 * @return the current tempo
	 */
	public float getBeatsPerMinute() {
		return bpm;
	}
	
	/**
	 * Allow the default stop on empty to be changed in the unlikely event playing
	 * should proceed even when there is nothing to play at any point in the future.
	 * @param soe
	 */
	public void setStopOnEmpty(boolean soe) {
		stopOnEmpty = soe;
	}
	
	protected void init() {
		setBpm(120, 0);
		accumMillis = 0L;		
	}
	
	public void setBpm(float bpm, long tick) {
		ticksPerMilli = source.getResolution() * bpm / 60000;
		this.bpm = bpm;	
        // start a new linear segment
        accumTicks = tick; // by definition
        synchronized ( milliLock ) {
            accumMillis += elapsedMillis;
            // about to be reset but ensure consistency for getMillisecondPosition()
            elapsedMillis = 0;
        }
        refMillis = getCurrentTimeMillis();
}

	protected void setRunning(boolean r) {
		running = r;
		setChanged();
		notifyObservers();		
	}
	
	protected void notesOff() {
		for ( Source.Track trk : eventSources() ) {
				trk.off(true);
			}			
		}		

	// to be called when pumping has stopped
	protected void stopped() {
		notesOff();
		setRunning(false);
	}
	
	protected List<Source.Track> eventSources() {
		if ( source == null ) {
			throw new IllegalStateException("MidiSource is null");
		}
		return source.getTracks();
	}
	
	protected void check(MidiEvent event) {
		MidiMessage msg = event.getMessage();
		if ( isMeta(msg) ) {
			if ( getType(msg) == TEMPO ) {
				setBpm(getTempo(msg), event.getTick());
			}
		}
	}
	
	// only to be called synchronously with real-time thread
	// we increment the values so the next pump has a millisecond interval to play
	protected void reposition(long millis, long tick) {
		accumTicks = (long)(tick + ticksPerMilli);
		synchronized ( milliLock ) {
			accumMillis = millis + 1;
			elapsedMillis = 0;
		}
		refMillis = getCurrentTimeMillis();
	}
	
	protected long getCurrentTimeMillis() {
		return System.nanoTime() / 1000000L;
	}

	protected long getCurrentTimeTicks() {
		return (long)(accumTicks + ticksPerMilli * elapsedMillis);
	}
	
	/**
	 * to be called when pumping
	 * @return true if peek() on all MidiSource.Events sources returne null, false otherwise.
	 */ 
	protected boolean pump() {
		Source.RepositionCommand cmd = source.sync(getCurrentTimeTicks());
		if ( cmd != null ) {
			reposition(cmd.getMillis(), cmd.getTick());
		}
		// repositioning means the current tick may have changed
		return pump(getCurrentTimeTicks());
	}
	
	/**
     * Pump MidiMessages as they become due.
     * @param targetTick the tick to pump until.
     * @return true if every peek() returned null, false otherwise
     */
    protected boolean pump(long targetTick) {
    	boolean empty = true;
    	for ( Source.Track trk : eventSources() ) {
    		while ( trk.getNextTick() <= targetTick ) {
                empty = false;
    			trk.playNext();
    		}
    	}
    	return empty;
    }

    /**
	 * PlayEngine encapsulates the real-time thread to avoid run() being public in MidiPlayer.
	 * @author st
	 *
	 */
	private class PlayEngine implements Runnable 
	{
		private Thread thread;

		PlayEngine() {
			// nearly MAX_PRIORITY
			int priority = Thread.NORM_PRIORITY
			+ ((Thread.MAX_PRIORITY - Thread.NORM_PRIORITY) * 3) / 4;
			thread = new Thread(this);
			thread.setName("Toot MidiPlayer - "+source.getName());
			thread.setPriority(priority);
			refMillis = getCurrentTimeMillis(); // prevent badval on 1st getTickPosition()
			thread.start();
		}
		
		public void stop() {
			thread = null;			
		}
		
		public void run() {
			refMillis = getCurrentTimeMillis();
			Thread thisThread = Thread.currentThread();
			boolean complete = false;
			while ( (thread == thisThread) && !complete ) {
				elapsedMillis = getCurrentTimeMillis() - refMillis;
				complete = pump() && stopOnEmpty;
				try {
					Thread.sleep(1);
				} catch (InterruptedException ie) {
					// ignore
				}
			}
			accumTicks = getCurrentTimeTicks()+1; // restart from next tick
			synchronized ( milliLock ) {
				accumMillis += elapsedMillis;
				elapsedMillis = 0;
			}
			stopped(); // turns off active notes, resets some controllers
		}
	}
}
