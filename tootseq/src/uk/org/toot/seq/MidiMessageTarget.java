// Copyright (C) 2014 Steve Taylor.
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
import uk.org.toot.midi.core.MidiTransport;

/**
 * This class uses the Decorator pattern to decorate a MidiTransport output in
 * order to track Midi note ons and send Midi note offs on mute and stop.
 * It encapsulates a note on cache to hide the messy details.
 * @author st
 */
public abstract class MidiMessageTarget implements MidiTransport
{
    /**
     * The implementation which is decorated
     */
    private MidiTransport impl;
    private NoteOnCache noteOnCache;

    public MidiMessageTarget(MidiTransport transport) {
        impl = transport;
        noteOnCache = new NoteOnCache();
    }

    public void transport(MidiMessage msg, long timestamp) {
        impl.transport(msg, 0);
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

    /**
     * Called for stop or mute
     * @param doControllers true for stop, false for mute
     */
    public void notesOff(boolean doControllers) {
        for ( int ch = 0; ch < 16; ch++ ) {
            int channelMask = (1 << ch);
            for ( int i = 0; i < 128; i++ ) {
                if ( noteOnCache.testAndClear(i, channelMask) ) {
                    // send note on with velocity 0
                    try {
                        impl.transport(NoteMsg.on(ch, i, 0), 0L);
                    } catch ( InvalidMidiDataException imda ) {					
                    }
                }
            }
            /* all notes off */
            try {
                impl.transport(createChannel(CONTROL_CHANGE, ch, ALL_NOTES_OFF), 0L);
            } catch ( InvalidMidiDataException imda ) {				
            }
            /* sustain off */
            try {
                impl.transport(createChannel(CONTROL_CHANGE, ch, HOLD_PEDAL, 0), 0L);
            } catch ( InvalidMidiDataException imda ) {				
            }
            if ( doControllers ) {
                /* reset all controllers */
                try {
                    impl.transport(createChannel(CONTROL_CHANGE, ch, ALL_CONTROLLERS_OFF), 0L);
                } catch ( InvalidMidiDataException imda ) {					
                }
            }
        }		
    }
    /**
     * A NoteOnCache is used for each MidiMessage destination in order that
     * active note ONs may be turned OFF on a stop condition or when muted.
     * @author st
     */
    public static class NoteOnCache
    {
        /**
         * bit-mask of notes that are currently on per channel
         */
        private int[] cache = new int[128];

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

