// Copyright (C) 2009, 2014, 2015 Steve Taylor.
// Distributed under the Toot Software License, Version 1.0. (See
// accompanying file LICENSE_1_0.txt or copy at
// http://www.toot.org.uk/LICENSE_1_0.txt)

package uk.org.toot.seq;

import static uk.org.toot.midi.message.MetaMsg.TEMPO;
import static uk.org.toot.midi.message.MetaMsg.getString;
import static uk.org.toot.midi.message.MetaMsg.getTempo;
import static uk.org.toot.midi.message.MetaMsg.getType;
import static uk.org.toot.midi.message.MetaMsg.isMeta;
import static uk.org.toot.midi.message.MetaMsg.TRACK_NAME;

import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;

import uk.org.toot.midi.core.MidiTransport;

/**
 * This class is an implementation of Source backed by a Midi Sequence.
 * Primarily as a simple example of how to concretize Source.
 * Broadly equivalent to the javasound Sequencer in that it uses a single
 * output for all tracks.
 * This class is not robust in the face of external edits to the Sequence.
 * 
 * @author st
 */
public class MidiSequenceSource extends Source
{
	private Sequence sequence;
	private MidiMessageTarget target;
	
	private List<SequenceTrack> tracks = 
		new java.util.ArrayList<SequenceTrack>();
	
	public MidiSequenceSource(Sequence sequence, MidiTransport transport) {
		this.sequence = sequence;
		target = new MidiMessageTarget(transport);
		javax.sound.midi.Track[] _tracks = sequence.getTracks();
		for ( int i = 0; i < _tracks.length; i++ ) {
			tracks.add(new SequenceTrack(i, _tracks[i]));
		}
        String nm = tracks.get(0).getName();
        name = nm == null ? "sequence" : nm;
	}
	
	public List<Track> getTracks() {
		return Collections.<Track>unmodifiableList(tracks); // costly?
	}
	
	public int getResolution() {
		return sequence.getResolution();
	}
	
	protected class SequenceTrack implements Track
	{
		private javax.sound.midi.Track track;
		private String name;
		private int index = 0;
		
		public SequenceTrack(int trk, javax.sound.midi.Track track) {
			this.track = track;
			String aname = getMetaName(TRACK_NAME);
			name = aname == null ? "Track "+(1+trk) : aname;
		}
		
		public long getNextTick() {
			if ( index >= track.size() ) return MAX_TICK;
			return track.get(index).getTick();
		}

		public void playNext() {
			if ( index >= track.size() ) return;
	        MidiMessage msg = track.get(index++).getMessage();
	        // MetaEvents should only occur on the first track
	        // but they should never be transported externally so we
	        // filter them out here and act on some of them anyway.
	        if ( isMeta(msg) ) {
	            if ( getType(msg) == TEMPO ) {
	                setBpm(getTempo(msg));
	            }
	        } else {
	            target.transport(msg, 0L);
	        }
		}
		
		public void off(boolean stop) {
		    target.notesOff(stop);
		}
		
		public String getName() {
			return name;
		}
		
	    protected String getMetaName(int type) {
	        MidiEvent event = getFirstMetaEvent(type);
	        if ( event == null ) return null;
	        MidiMessage msg = event.getMessage();
	        if ( isMeta(msg) ) {
	            return getString(msg);
	        }
	        return null;
	    }

	    protected MidiEvent getFirstMetaEvent(int type) {
	        for ( int i = 0; i < track.size() - 1; i++ ) {
	            MidiEvent event = track.get(i);
	            MidiMessage msg = event.getMessage();
	            if ( isMeta(msg) ) {
	                if (getType(msg) == type) {
	                    return event;
	                }
	            }
	        }
	        return null;
	    }
	}
}
