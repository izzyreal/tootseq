// Copyright (C) 2009, 2010, 2014 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import java.util.Observable;

/**
 * Sequencer times events played back from a Source.
 *
 * @author st 
 */
public class Sequencer extends Observable
{
    private static float MINUTES_PER_MILLISECOND = 1f / 60000;

    private PlayEngine playEngine = null;
    private SynchronousControl control = new SynchronousControl();
    private float tempoFactor = 1f;     // not reset     

    private Source source;
    private float bpm;                  // tempo, beats per minute
    private int ticksPerQuarter;        // source resolution
    private long tickPosition;          // accumulated ticks
    private float deltaTicks;           // pre-wrap tick delta

    public void setSource(Source source) {
        if ( isRunning() ) {
            throw new IllegalStateException("Can't set Source while playing");
        }
        this.source = source;
        checkSource();
        bpm = 120f;
        ticksPerQuarter = source.getResolution();
        tickPosition = 0;
        deltaTicks = 0f;
        source.control(control);
        source.stopped();
    }

    /**
     * Start playing
     */
    public void play() {
        checkSource();
        if ( isRunning() ) return;
        playEngine = new PlayEngine();
        setChanged();
        notifyObservers();      
    }

    /**
     * Commence stopping.
     */
    public void stop() {
        if ( !isRunning() ) return;
        playEngine.stop();
        // observers are notified after the engine actually stops
    }

    /**
     * Return whether we're currently playing.
     * Note that observers are notified immediately subsequent to transitions.
     * @return true if playing (or stopping), false if stopped
     */
    public boolean isRunning() {
        return playEngine != null;
    }

    /**
     * Get the current tick position.
     * @return the tick position in the Source
     */
    public long getTickPosition() {
        return tickPosition;
    }

    // typically through the SynchronousControl object
    public void setBpm(float bpm) {
        this.bpm = bpm;
    }

    /**
     * Get the requested tempo in beats per minute.
     * The actual tempo qill be scaled by the tempo factor.
     * @return the current tempo
     */
    public float getBpm() {
        return bpm;
    }

    /**
     * Set the tempo factor, sensible bounds should be enforced by the caller
     * as apprpriate.
     * @param f the tempo factor
     */
    public void setTempoFactor(float f) {
        tempoFactor = f;
    }

    /**
     * @return the tempo factor
     */
    public float getTempoFactor() {
        return tempoFactor;
    }

    protected void checkSource() {
        if ( source == null ) {
            throw new IllegalStateException("Source is null");
        }
    }

    // called when pumping has stopped
    protected void stopped() {
        source.stopped();
        playEngine = null;
        setChanged();
        notifyObservers();      
    }

    // should be accurate to better than a microsecond on moder platforms
    protected long getCurrentTimeMicros() {
        return System.nanoTime() / 1000L;
    }

    // split out from pump so we can sync before first timing interval
    protected void sync() {
       tickPosition += source.sync(tickPosition);
       source.playToTick(tickPosition);
    }
    
    // sync if the tick changes during this timing interval
    protected void pump(int deltaMicros) {
        float deltaMinutes = deltaMicros * 0.001f * MINUTES_PER_MILLISECOND;
        deltaTicks += deltaMinutes * bpm * ticksPerQuarter * tempoFactor;
        if ( deltaTicks >= 1f ) {
            int nTicks = (int)deltaTicks;
            deltaTicks -= nTicks;
            tickPosition += nTicks;
            sync();
        }
    }

    /**
     * PlayEngine encapsulates the real-time thread to avoid run() being public.
     */
    private class PlayEngine implements Runnable 
    {
        private Thread thread;
        private long prevMicros, nowMicros;

        PlayEngine() {
            // nearly MAX_PRIORITY
            int priority = Thread.NORM_PRIORITY
                + ((Thread.MAX_PRIORITY - Thread.NORM_PRIORITY) * 3) / 4;
            thread = new Thread(this);
            thread.setName("Toot Sequencer - "+source.getName());
            thread.setPriority(priority);
            thread.start();
        }

        public void stop() {
            thread = null;			
        }

        public void run() {
            prevMicros = getCurrentTimeMicros();
            sync(); // first sync at start tick
            Thread thisThread = Thread.currentThread();
            while ( thread == thisThread ) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ie) {
                    // ignore
                }
                nowMicros = getCurrentTimeMicros();
                pump((int)(nowMicros - prevMicros));
                prevMicros = nowMicros;
            }
            stopped(); // turns off active notes, resets some controllers
        }
    }

    /**
     * private to prevent instantiation by any other class
     * exposes the synchronous interface to the Source privately
     */
    private class SynchronousControl implements Source.SynchronousControl
    {
        public void setBpm(float bpm) {
            Sequencer.this.setBpm(bpm);

        }	    
    }
}
