// Copyright (C) 2009, 2010, 2014, 2015 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import java.util.Observable;

/**
 * Sequencer times events played back from a Source.
 * Either Master or Slave timing
 *
 * @author st 
 */
public class Sequencer extends Observable
{
    private static float MINUTES_PER_MICROSECOND = 1f / 60000000;

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
        deriveClockMultiplier();
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
        playEngine = new PlayEngine(createClock());
        setChanged();
        notifyObservers();      
    }

    /**
     * Commence stopping
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
        if ( clocksPerQuarter != 0 ) return 1f; // slave clock ignores tempo factor
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
       long offset = source.sync(tickPosition);
       tickPosition += offset;
       if ( offset != 0 ) {
           lastTickPosition = tickPosition; // !!! concurrency safe? design safe?
       }
       source.playToTick(tickPosition);
    }
    
    /**
     * PlayEngine encapsulates the real-time thread to avoid run() being public.
     */
    private class PlayEngine implements Runnable 
    {
        private Thread thread;
        private long prevMicros, nowMicros;
        private Clock clock;

        PlayEngine(Clock clock) {
            this.clock = clock;
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
                clock.interval((int)(nowMicros - prevMicros));
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
    
    /**
     * Create the appropriate clock i.e. master or slave
     * @return a Clock implementation
     */
    protected Clock createClock() {
        return clocksPerQuarter == 0 ? new MasterClock() : new SlaveClock();
    }
    
    /**
     * contract for handling microsecond time intervals
     * typically by deriving tickPosition and calling sync()
     * @author st
     */
    private interface Clock
    {
        public void interval(int deltaMicros);
    }
    
    /**
     * implementation for a master clock
     * @author st
     */
    private class MasterClock implements Clock
    {
        @Override
        public void interval(int deltaMicros) {
            float deltaMinutes = deltaMicros * MINUTES_PER_MICROSECOND;
            deltaTicks += deltaMinutes * bpm * ticksPerQuarter * tempoFactor;
            if ( deltaTicks >= 1f ) {
                int nTicks = (int)deltaTicks;
                deltaTicks -= nTicks;
                tickPosition += nTicks;
                sync();
            }           
        }        
    }
    
    // variables for SlaveClock
    private long jamTickPosition  = 0;
    private long lastTickPosition = 0;
    private long slaveClockMicros = 0;
    private int clockMultiplier = 1;
    private int clocksPerQuarter = 0;       // master clock
    private float loopCoeff = 0.25f;
 
    /**
     * Pass 24 or 48 or similar for slave clocks, 0 for master clock
     * @param pq the clocks per quarter note, must be greater than ticksPerQuarter
     */
    public void setClocksPerQuarter(int pq) {
        if ( isRunning() ) return;
        clocksPerQuarter = pq;
        if ( clocksPerQuarter == 0 ) return; // master clock
        lastTickPosition = tickPosition; //?
        slaveClockMicros = 0;
        deriveClockMultiplier();
    }
    
    /**
     * called on setSource() and setClocksPerQuarter() such that clockMultiplier is derived
     * correctly whichever call is made first when SlaveClock is used
     */
    protected void deriveClockMultiplier() {
        if ( clocksPerQuarter == 0 || ticksPerQuarter == 0 ) return;    // too early or master rather than slave
        assert (ticksPerQuarter % clocksPerQuarter) == 0;               // ensure clockMultiplier will be valid
        assert ticksPerQuarter >= clocksPerQuarter;                     // ensure clockMultiplier will be valid
        clockMultiplier = ticksPerQuarter / clocksPerQuarter;
    }
    
    /**
     * Call on each external clock
     */
    public void clock() {
        if ( clocksPerQuarter == 0 ) return; // master clock
        jamTickPosition = lastTickPosition + clockMultiplier;
        lastTickPosition = jamTickPosition; // because jamTickPosition is cleared by SlaveClock
        long timeMicros = getCurrentTimeMicros();
        if ( slaveClockMicros == 0 ) {
            slaveClockMicros = timeMicros;
            // first clock so we don't have an interval time yet
            // so we don't update bpm and hope things sort themselves out before anyone notices :)
            return;
        }
        long deltaMicros = timeMicros - slaveClockMicros;
        if ( deltaMicros == 0 ) return;     // shouldn't happen but prevent a divide by zero
        slaveClockMicros = timeMicros;
        float deltaMinutes = deltaMicros * MINUTES_PER_MICROSECOND;
        // since each clock corresponds to clockMultiplier ticks, borrowing from MasterClock
        // clockMultiplier = deltaMinutes * bpm * ticksPerQuarter;
        // bpm = clockMultiplier / (deltaMinutes * ticksPerQuarter);                    // rearranging for bpm
        // bpm = ticksPerQuarter / (deltaMinutes * ticksPerQuarter * clockPerQuarter);  // substituting for clockMultiplier
        float abpm = 1f / (deltaMinutes * clocksPerQuarter);                            // cancelling ticksperQuarter
        if ( abpm > 300 ) return;                                                       // ignore bogus values
        bpm = loopCoeff * abpm + (1f - loopCoeff) * bpm;
    }
 
    /**
     * implementation for a slave clock
     * @author st
     */
    private class SlaveClock implements Clock
    {
        private int count = 0; // inhibit interpolation until jammed

        public SlaveClock() {
            assert clockMultiplier >= 1;                            // ensure clockMultiplier is valid
        }
        
        @Override
        public void interval(int deltaMicros) {
            if ( jamTickPosition > 0 ) {
                tickPosition = jamTickPosition;
                jamTickPosition = 0;
                sync();
                // prepare for interpolated ticks
                deltaTicks = 0;
                count = clockMultiplier - 1; // ticks to freerun interpolate
                return;
            }
            float deltaMinutes = deltaMicros * MINUTES_PER_MICROSECOND;
            deltaTicks += deltaMinutes * bpm * ticksPerQuarter;
            if ( deltaTicks >= 1f && count > 0 ) {
                int nTicks = (int)deltaTicks;
                deltaTicks -= nTicks;
                tickPosition += nTicks;
                sync();
                count -= nTicks;
            }
        }
        
    }
}
