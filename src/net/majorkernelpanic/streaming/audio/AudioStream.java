/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.audio;

import java.io.IOException;

import net.majorkernelpanic.streaming.MediaStream;
import android.media.MediaRecorder;
import android.util.Log;

/** 
 * ��ֱ��ʹ������ࡣ
 */
public abstract class AudioStream  extends MediaStream {

	protected int mAudioSource;
	protected int mOutputFormat;
	protected int mAudioEncoder;
	protected AudioQuality mRequestedQuality = AudioQuality.DEFAULT_AUDIO_QUALITY.clone();
	protected AudioQuality mQuality = mRequestedQuality.clone();
	
	public AudioStream() {
		setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
	}
	
	public void setAudioSource(int audioSource) {
		mAudioSource = audioSource;
	}

	public void setAudioQuality(AudioQuality quality) {
		mRequestedQuality = quality;
	}
	
	/** 
	 * ��������������
	 */
	public AudioQuality getAudioQuality() {
		return mQuality;
	}	
	
	protected void setAudioEncoder(int audioEncoder) {
		mAudioEncoder = audioEncoder;
	}
	
	protected void setOutputFormat(int outputFormat) {
		mOutputFormat = outputFormat;
	}
	
	@Override
	protected void encodeWithMediaRecorder() throws IOException {
		
		// ������Ҫһ������socket��������������ת��������
		createSockets();

		Log.v(TAG,"Requested audio with "+mQuality.bitRate/1000+"kbps"+" at "+mQuality.samplingRate/1000+"kHz");
		
		mMediaRecorder = new MediaRecorder();
		mMediaRecorder.setAudioSource(mAudioSource);
		mMediaRecorder.setOutputFormat(mOutputFormat);
		mMediaRecorder.setAudioEncoder(mAudioEncoder);
		mMediaRecorder.setAudioChannels(1);
		mMediaRecorder.setAudioSamplingRate(mQuality.samplingRate);
		mMediaRecorder.setAudioEncodingBitRate(mQuality.bitRate);
		
		//����д����������һ������socket�������ļ�!
		//��һ��С��������������ʵ��:���ݴ����
		//Ȼ����Բ��ݵ���һ��socket
		mMediaRecorder.setOutputFile(mSender.getFileDescriptor());

		mMediaRecorder.prepare();
		mMediaRecorder.start();

		try {
			// mReceiver��getInputStream�����������������
			// mPacketizer��һ��RTP����װ�������ͨ�����緢��
			mPacketizer.setDestination(mDestination, mRtpPort, mRtcpPort);
			mPacketizer.setInputStream(mReceiver.getInputStream());
			mPacketizer.start();
			mStreaming = true;
		} catch (IOException e) {
			stop();
			throw new IOException("Something happened with the local sockets :/ Start failed !");
		}
		
	}
	
}
