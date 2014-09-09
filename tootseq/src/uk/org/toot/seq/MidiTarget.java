// Copyright (C) 2009 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import static uk.org.toot.midi.message.ChannelMsg.CONTROL_CHANGE;
import static uk.org.toot.midi.message.ChannelMsg.createChannel;
import static uk.org.toot.midi.misc.Controller.ALL_CONTROLLERS_OFF;
import static uk.org.toot.midi.misc.Controller.ALL_NOTES_OFF;
import static uk.org.toot.midi.misc.Controller.HOLD_PEDAL;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.ShortMessage;

import uk.org.toot.midi.message.NoteMsg;

/**
 * MidiTarget extends MidiSource to provide a 1:1 mapping between EventSources and
 * MessageTargets. This allows the source of MidiEvents to also control the destination
 * of the resulting MidiMessages.
 * Only MidiPlayer makes use of MidiTarget.
 * @author st
 *
 */
public abstract class MidiTarget extends Source
{
	public interface MessageTarget
	{
		public void transport(MidiMessage msg);
		
		/**
		 * Called for stop or mute
		 * @param doControllers true for stop, false for mute
		 */
		public void notesOff(boolean doControllers);
	}

	/**
	 * This class concretizes MessageTarget and encapsulates all handling of
	 * its NoteOnCache.
	 * transport() is implemented to track active notes in the NoteOnCache.
	 * notesOff() is implemented to turn off notes from the NoteOnCache.
	 * transportImpl() should be implemented by subclasses.
	 * @author st
	 */
	public abstract static class AbstractMessageTarget implements MessageTarget, Track
	{
		private NoteOnCache noteOnCache;
		
		public AbstractMessageTarget() {
			noteOnCache = new NoteOnCache();
		}
		
		public void transport(MidiMessage msg) {
			transportImpl(msg);
			int msgStatus = msg.getStatus();
			int chan = msgStatus & 0x0F;
			int note;
			switch ( msgStatus & 0xF0 ) {
			case ShortMessage.NOTE_OFF:
				note = ((ShortMessage) msg).getData1() & 0x7F;
				noteOnCache.clear(note, chan);
				break;

			case ShortMessage.NOTE_ON:
				ShortMessage smsg = (ShortMessage) msg;
				note = smsg.getData1() & 0x7F;
				int vel = smsg.getData2() & 0x7F;
				if ( vel > 0 ) {
					noteOnCache.set(note, chan);
				} else {
					noteOnCache.clear(note, chan);
				}
				break;
			}
		}

		public void notesOff(boolean doControllers) {
			for ( int ch = 0; ch < 16; ch++ ) {
				int channelMask = (1 << ch);
				for ( int i = 0; i < 128; i++ ) {
					if ( noteOnCache.testAndClear(i, channelMask) ) {
						// send note on with velocity 0
						try {
							transportImpl(NoteMsg.on(ch, i, 0));
						} catch ( InvalidMidiDataException imda ) {					
						}
					}
				}
				/* all notes off */
				try {
					transportImpl(createChannel(CONTROL_CHANGE, ch, ALL_NOTES_OFF));
				} catch ( InvalidMidiDataException imda ) {				
				}
				/* sustain off */
				try {
					transportImpl(createChannel(CONTROL_CHANGE, ch, HOLD_PEDAL, 0));
				} catch ( InvalidMidiDataException imda ) {				
				}
				if ( doControllers ) {
					/* reset all controllers */
					try {
						transportImpl(createChannel(CONTROL_CHANGE, ch, ALL_CONTROLLERS_OFF));
					} catch ( InvalidMidiDataException imda ) {					
					}
				}
			}		
		}

		public abstract void transportImpl(MidiMessage msg);
	}
		
	/**
	 * A NoteOnCache is used for each MidiMessage destination in order that
	 * active note ONs may be turned OFF on a stop condition or when muted.
	 * @author st
	 */
	public static class NoteOnCache
	{
		private int[] cache = new int[128]; // bit-mask of notes that are currently on

		/**
		 * Call for note ON with velocity > 0
		 * @param note the pitch to set
		 * @param ch the channel
		 */
		public void set(int note, int ch) {
			cache[note] |= 1 << ch;
		}
		
		/**
		 * Call for note OFF and note ON with velocity == 0
		 * @param note the pitch to set
		 * @param ch the channel
		 */
		public void clear(int note, int ch) {
			cache[note] &= (0xFFFF ^ (1 << ch));	
		}
		
		/**
		 * Return true if pitch is active on channel, and clear relevant bit if
		 * so, otherwise return false.
		 * 
		 * @param i the pitch to test
		 * @param ch the channel to test
		 * @return true if pitch is active on channel
		 */
		public boolean testAndClear(int i, int ch) {
			int channelMask = (1 << ch);
			if ((cache[i] & channelMask) != 0) {
				cache[i] ^= channelMask;
				return true;
			}
			return false;
		}
		
		/**
		 * Clear the entire cache
		 */
		public void clear() {
			for ( int i = 0; i < 128; i++ ) {
				cache[i] = 0;
			}
		}
	}
}
