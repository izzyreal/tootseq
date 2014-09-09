// Copyright (C) 2009, 2010, 2014 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import java.util.Observable;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;

import static uk.org.toot.midi.message.MetaMsg.*;

/**
 * Sequencer events from Sources in real-time. 
 *
 * @author st 
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
	
    protected Source source;

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

	public void setSource(Source source) {
		if ( running ) {
			throw new IllegalStateException("Can't set Source while playing");
		}
        this.source = source;
        checkSource();
        init();
        source.returnToZero(); // just in case it isn't
		source.sync(0); // quickly inform source we support syncing
        source.stop();
	}

	/**
	 * Start playing
	 */
	public void play() {
        checkSource();
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
	 * As if setSource() had been called again.
	 */
	public void returnToZero() {
        checkSource();
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
	 * @return the tick position in the Source
	 */
	public long getTickPosition() {
		return getCurrentTimeTicks();
	}
	
	/**
	 * Get the current millisecond position
	 * @return the millisecond position in the Source
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
	public float getBpm() {
		return bpm;
	}
	
    /**
     * Set the bpm starting at the tick
     * @param bpm beats per minute
     * @param tick
     */
    public void setBpm(float bpm, long tick) {
        checkSource();
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

    // !!! TODO move elsewhere related to midi
    protected void check(MidiEvent event) {
        MidiMessage msg = event.getMessage();
        if ( isMeta(msg) ) {
            if ( getType(msg) == TEMPO ) {
                setBpm(getTempo(msg), event.getTick());
            }
        }
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

	protected void checkSource() {
	    if ( source == null ) {
	        throw new IllegalStateException("Source is null");
        }
	}

	protected void setRunning(boolean r) {
		running = r;
		setChanged();
		notifyObservers();		
	}
	
	// to be called when pumping has stopped
	protected void stopped() {
        source.stop();
		setRunning(false);
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
	 * to be called when pumping.
	 * @return true every Track has nothing left to play.
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
     * Pump events as they become due.
     * @param targetTick the tick to pump until.
     * @return true if every Track has nothing left to play
     */
    protected boolean pump(long targetTick) {
    	boolean empty = true;
    	for ( Source.Track trk : source.getTracks() ) {
    		while ( trk.getNextTick() <= targetTick ) {
                empty = false;
    			trk.playNext();
    		}
    	}
    	return empty;
    }

    /**
	 * PlayEngine encapsulates the real-time thread to avoid run() being public.
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
