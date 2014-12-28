// Copyright (C) 2009, 2010, 2014 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;

import uk.org.toot.midi.message.CommonMsg;
import uk.org.toot.midi.message.TimeMsg;
import uk.org.toot.midi.message.MTC;
import uk.org.toot.midi.core.MidiTransport;

/**
 * A Sequencer that generates Quarter Frame MTC messages.
 * MTC messages are sent to the provided MidiTransport.
 * MTC Messages are sent on the nearest millisecond, this has minimal jitter for
 * 25 fps and up to 0.5 millisecond jitter for 24 and 30 fps, plus any thread
 * timing jitter.
 * We also send a Full MTC message on returnToZero().
 * Default frame rate is 25fps.
 * 
 * @author st
 */
public class MTCSequencer extends Sequencer
{
	private boolean mtcEnabled = false;
	private int prevqf = -1;	// previous quarter frame index, force next to be 0
	private float mspf;			// milliseconds per frame
	private float mspqf;		// milliseconds per quarter frame
	private float qfpms;		// quarter frames per millisecond
	private int failures = 0;

	private MTC.Time time = new MTC.Time();
	private MTC.FrameRate rate;
	private MTC.FrameRate requestedRate;
	
	private MidiTransport port;
	
	public MTCSequencer(MidiTransport transport) {
        port = transport;
		setMTCFrameRate(MTC.FrameRate.FPS_25);
	}
	
	/**
	 * Set whether MTC is enabled.
	 * @param enabled true if MTC should be generated, false if not.
	 */
	public void setMTCEnabled(boolean enabled) {
		mtcEnabled = enabled;
	}
	
	/**
	 * @return whether MTC is enabled
	 */
	public boolean isMTCEnabled() {
		return mtcEnabled;
	}
	
	/**
	 * Get the current MTC Frame Rate
	 * @return the current MTC Frame Rate
	 */
	public MTC.FrameRate getMTCFrameRate() {
		return rate;
	}
	
	/**
 	 * Set the MTC Frame Rate in frames per second
	 * @param fps frame per second, either 24, 25 or 30, default 25
	 */
	public void setMTCFrameRate(MTC.FrameRate rate) {
		if ( rate == this.rate ) return; // no change
		if ( rate == MTC.FrameRate.FPS_30DF ) {
			throw new IllegalArgumentException("Drop Frame not supported");
		}
		if ( !isRunning() ) {
			setMTCFrameRateImpl(rate);
		}
		requestedRate = rate;
	}
	
	// called synchronously with real-time thread if running
	protected void setMTCFrameRateImpl(MTC.FrameRate rate) {
		this.rate = rate;
		mspf = 1000f / rate.getRate();	
		mspqf = mspf / 4;	
		qfpms = 1 / mspqf;
	}
	
	@Override
	protected void pump() {
		super.pump();
		if ( mtcEnabled ) checkQFdue(getMillisecondPosition());
	}
	
	/**
	 * Encode hh:mm:ss:ff into quarter frame MTC if due at this time
	 * @param millis the time in milliseconds
	 */
	protected void checkQFdue(long millis) {
		// synchronize requested rate change
		if ( rate != requestedRate ) {
			setMTCFrameRateImpl(requestedRate);
		}
		// we want to count 8 quarter frames every two frames
		// there are fps frames per second, so fps quarter frames per 250 milliseconds
		// we modulus by 250 because all fps's have integral number of quarter frames
		// and we want to prevent arithmetic errors with large millis
		// we divide by milliseconds per frame to get the frame float
		// we modulus by 2 because 8 quarter frames take 2 frames
		float f = ((float)(millis % 250) / mspf) % 2f;
		// we enable rounding by adding half of a quarter frame per millisecond
		// this ensures the quarter frame is detected on the nearest millisecond
		float qff = qfpms / 2 + 4 * f;
		// we modulus by 8 because rounding addition may have gone above 8
		int qf = (int)qff % 8;
		if ( qf != prevqf ) {
			if ( qf == 0 ) {	// cache for all 8 quarter frames
				time.frames = Math.round((float)(millis % 1000) / mspf);
				long s = millis / 1000; 		// total seconds
				time.seconds = (int) (s % 60); 	// second within minute
				long m = s / 60; 				// total minutes
				time.minutes = (int) (m % 60); 	// minutes within hour
				long h = m / 60; 				// total hours
				time.hours = (int) (h % 24); 	// hours within day
			}
			try {
				MidiMessage msg = CommonMsg.createMTCQuarterFrame(qf, time, rate);
				if ( msg != null ) {
					port.transport(msg, 0L);
				}
			} catch ( InvalidMidiDataException imde ) {
				failures++;
				if ( failures == 1 ) 
					System.err.println("Failed to create MTC Quarter Frame message");
			}
			prevqf = qf;
		}		
	}
	
	@Override
	public void returnToZero() {
		super.returnToZero();
		// send Full MTC message
		time.clear();
		try {
			port.transport(TimeMsg.createMTCFull(0x7f, time, rate), 0L);
		} catch ( InvalidMidiDataException imde ) {
			failures++;
			System.err.println("Failed to create MTC Full message");
		}
	}
}
