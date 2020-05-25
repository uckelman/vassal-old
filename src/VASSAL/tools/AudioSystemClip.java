/*
 * $Id$
 *
 * Copyright (c) 2008-2009 by Brent Easton
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */
package VASSAL.tools;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

import VASSAL.tools.AudioClip;

public class AudioSystemClip implements AudioClip {
  protected Clip the_clip;

  protected Clip getClip(InputStream in) throws IOException {
    if (!in.markSupported()) {
      // AudioInputStream requires a stream which is markable
      in = new BufferedInputStream(in);
      // TODO stream is never closed, close it?
    }

    // try to get a Clip
    try (Clip clip = AudioSystem.getClip()) {
      // wrap the input stream
      try (AudioInputStream ais = AudioSystem.getAudioInputStream(in)) {
        // convert the audio stream to the type the clip wants
        try (AudioInputStream cais = AudioSystem.getAudioInputStream(clip.getFormat(), ais)) {
          try {
            clip.open(cais);
            return clip;
          }
          catch (IllegalArgumentException | SecurityException | LineUnavailableException e) {
            throw new IOException(e);
          }
        }
        catch (IllegalArgumentException e) {
          throw new IOException(e);
        }
      }
      catch (UnsupportedAudioFileException e) {
        throw new IOException(e);
      }
    }
    catch (IllegalArgumentException | SecurityException | LineUnavailableException e) {
      throw new IOException(e);
    }
    catch (Exception e) {
      throw e;
    }
  }

  public AudioSystemClip(URL url) throws IOException {
    try (InputStream in = url.openStream()) {
      the_clip = getClip(in);
    }
  }

  public AudioSystemClip(InputStream in) throws IOException {
    the_clip = getClip(in);
  }

  @Override
  public void play() {
    the_clip.setFramePosition(0);
    the_clip.start();
  }
}
