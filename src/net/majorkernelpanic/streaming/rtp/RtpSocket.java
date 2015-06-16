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

package net.majorkernelpanic.streaming.rtp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.majorkernelpanic.streaming.rtcp.SenderReport;
import android.os.SystemClock;
import android.util.Log;

/**
 * ����ʵ��RTP��socket��
 * ��ʵ����һ���������,����FIFO�����һ���̡߳�
 * ����,���һ���������ͼ���Ͷ�����ݰ�̫��,FIFO�������������ݰ���һ����һ��˳�����͡�
 */
public class RtpSocket implements Runnable {

	public static final String TAG = "RtpSocket";

	public static final int RTP_HEADER_LENGTH = 12;
	public static final int MTU = 1300;

	private MulticastSocket mSocket;
	private DatagramPacket[] mPackets;
	private byte[][] mBuffers;
	private long[] mTimestamps;

	private SenderReport mReport;
	
	private Semaphore mBufferRequested, mBufferCommitted;
	private Thread mThread;

	private long mCacheSize;
	private long mClock = 0;
	private long mOldTimestamp = 0;
	private int mSsrc, mSeq = 0, mPort = -1;
	private int mBufferCount, mBufferIn, mBufferOut;
	private int mCount = 0;
	
	private AverageBitrate mAverageBitrate;

	/**
	 * ���RTP socketʵ�ֻ����������FIFO�����һ���̡߳�
	 * @throws IOException
	 */
	public RtpSocket() {
		
		mCacheSize = 00;
		mBufferCount = 300; // TODO: ������Ӧ,��FIFO�Ѿ�����
		mBuffers = new byte[mBufferCount][];
		mPackets = new DatagramPacket[mBufferCount];
		mReport = new SenderReport();
		mAverageBitrate = new AverageBitrate();
		
		resetFifo(); 

		for (int i=0; i<mBufferCount; i++) {

			mBuffers[i] = new byte[MTU];
			mPackets[i] = new DatagramPacket(mBuffers[i], 1);

			/*							     Version(2)  Padding(0)					 					*/
			/*									 ^		  ^			Extension(0)						*/
			/*									 |		  |				^								*/
			/*									 | --------				|								*/
			/*									 | |---------------------								*/
			/*									 | ||  -----------------------> Source Identifier(0)	*/
			/*									 | ||  |												*/
			mBuffers[i][0] = (byte) Integer.parseInt("10000000",2);

			/* ��Ч�������� */
			mBuffers[i][1] = (byte) 96;

			/* Byte 2,3        ->  Sequence Number                   */
			/* Byte 4,5,6,7    ->  Timestamp                         */
			/* Byte 8,9,10,11  ->  Sync Source Identifier            */

		}

		try {
		mSocket = new MulticastSocket();
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}
		
	}

	private void resetFifo() {
		mCount = 0;
		mBufferIn = 0;
		mBufferOut = 0;
		mTimestamps = new long[mBufferCount];
		mBufferRequested = new Semaphore(mBufferCount);
		mBufferCommitted = new Semaphore(0);
		mReport.reset();
		mAverageBitrate.reset();
	}
	
	/** �رյײ��socket�� */
	public void close() {
		mSocket.close();
	}

	/** ��������SSRC��������ʶRTP��Ϣ��������Դ�����µ���Ϣ������ʼʱԴ����������һ�����룩 */
	public void setSSRC(int ssrc) {
		this.mSsrc = ssrc;
		for (int i=0;i<mBufferCount;i++) {
			setLong(mBuffers[i], ssrc,8,12);
		}
		mReport.setSSRC(mSsrc);
	}

	/** ��������SSRC */
	public int getSSRC() {
		return mSsrc;
	}

	/** ��������ʱ��Ƶ�ʣ�Hz */
	public void setClockFrequency(long clock) {
		mClock = clock;
	}

	/** ����FIFO�Ĵ�С ms. */
	public void setCacheSize(long cacheSize) {
		mCacheSize = cacheSize;
	}
	
	/** ����UDP���ݰ��Ĵ��ʱ�� */
	public void setTimeToLive(int ttl) throws IOException {
		mSocket.setTimeToLive(ttl);
	}

	/** ����Ŀ�ĵ�ַ,���ݰ��������͡�*/
	public void setDestination(InetAddress dest, int dport, int rtcpPort) {
		mPort = dport;
		for (int i=0;i<mBufferCount;i++) {
			mPackets[i].setPort(dport);
			mPackets[i].setAddress(dest);
		}
		mReport.setDestination(dest, rtcpPort);
	}

	public int getPort() {
		return mPort;
	}

	public int getLocalPort() {
		return mSocket.getLocalPort();
	}

	public SenderReport getRtcpSocket() {
		return mReport;
	}
	
	/** 
	 * ��FIFO����һ����Ч�����ݣ������Ա��޸� 
	 * ����{@link #commitBuffer(int)} ͨ�����緢�� 
	 * @throws InterruptedException 
	 **/
	public byte[] requestBuffer() throws InterruptedException {
		mBufferRequested.acquire();
		mBuffers[mBufferIn][1] &= 0x7F;
		return mBuffers[mBufferIn];
	}

	/** �ѻ�FIFO�����޷��������ݰ���*/
	public void commitBuffer() throws IOException {

		if (mThread == null) {
			mThread = new Thread(this);
			mThread.start();
		}
		
		if (++mBufferIn>=mBufferCount) mBufferIn = 0;
		mBufferCommitted.release();

	}	
	
	/** ͨ�����緢��RTP���ݰ���*/
	public void commitBuffer(int length) throws IOException {
		updateSequence();
		mPackets[mBufferIn].setLength(length);

		mAverageBitrate.push(length);

		if (++mBufferIn>=mBufferCount) mBufferIn = 0;
		mBufferCommitted.release();

		if (mThread == null) {
			mThread = new Thread(this);
			mThread.start();
		}		
		
	}

	/** ����һ�����Ƶ�RTP���ı�����
	 * Returns an approximation of the bitrate of the RTP stream in bit per seconde. */
	public long getBitrate() {
		return mAverageBitrate.average();
	}

	/** �������к��롣 */
	private void updateSequence() {
		setLong(mBuffers[mBufferIn], ++mSeq, 2, 4);
	}

	/** 
	 * �����ڰ�ʱ�����
	 * @param timestamp �µ�ʱ���  ns.
	 **/
	public void updateTimestamp(long timestamp) {
		mTimestamps[mBufferIn] = timestamp;
		setLong(mBuffers[mBufferIn], (timestamp/100L)*(mClock/1000L)/10000L, 4, 8);
	}

	/** ����RTP���ݰ��ı�ǡ� */
	public void markNextPacket() {
		mBuffers[mBufferIn][1] |= 0x80;
	}

	/**�߳����Ƚ��ȳ�һ����һ���Ժ㶨���ʷ������ݰ���  */
	@Override
	public void run() {
		Statistics stats = new Statistics(50,3000);
		try {
			// ����FIFO����mCacheSize���롣
			Thread.sleep(mCacheSize);
			long delta = 0;
			while (mBufferCommitted.tryAcquire(4,TimeUnit.SECONDS)) {
				if (mOldTimestamp != 0) {
					// ����ʹ�����ǵ�����ʱ��Ƶ�ʺ�����ʱ���֮��Ĳ���������������ʱ�����š�
					if ((mTimestamps[mBufferOut]-mOldTimestamp)>0) {
						stats.push(mTimestamps[mBufferOut]-mOldTimestamp);
						long d = stats.average()/1000000;
						Log.d(TAG,"delay: "+d+" d: "+(mTimestamps[mBufferOut]-mOldTimestamp)/1000000);
						//����RtpSocket�����ʹ�õ�����ȷ�������ٶȺ㶨����ƥ������ݰ���
						if (mCacheSize>0) Thread.sleep(d);
					} else if ((mTimestamps[mBufferOut]-mOldTimestamp)<0) {
						Log.e(TAG, "TS: "+mTimestamps[mBufferOut]+" OLD: "+mOldTimestamp);
					}
					delta += mTimestamps[mBufferOut]-mOldTimestamp;
					if (delta>500000000 || delta<0) {
						Log.d(TAG,"permits: "+mBufferCommitted.availablePermits());
						delta = 0;
					}
				}
				mReport.update(mPackets[mBufferOut].getLength(), System.nanoTime(),(mTimestamps[mBufferOut]/100L)*(mClock/1000L)/10000L);
				mOldTimestamp = mTimestamps[mBufferOut];
				if (mCount++>30) mSocket.send(mPackets[mBufferOut]);
				if (++mBufferOut>=mBufferCount) mBufferOut = 0;
				mBufferRequested.release();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		mThread = null;
		resetFifo();
	}

	private void setLong(byte[] buffer, long n, int begin, int end) {
		for (end--; end >= begin; end--) {
			buffer[end] = (byte) (n % 256);
			n >>= 8;
		}
	}

	/** 
	 * ����ƽ�������ʡ�
	 **/
	protected static class AverageBitrate {

		private final static long RESOLUTION = 200;
		
		private long mOldNow, mNow, mDelta;
		private long[] mElapsed, mSum;
		private int mCount, mIndex, mTotal;
		private int mSize;
		
		public AverageBitrate() {
			mSize = 5000/((int)RESOLUTION);
			reset();
		}
		
		public AverageBitrate(int delay) {
			mSize = delay/((int)RESOLUTION);
			reset();
		}
		
		public void reset() {
			mSum = new long[mSize];
			mElapsed = new long[mSize];
			mNow = SystemClock.elapsedRealtime();
			mOldNow = mNow;
			mCount = 0;
			mDelta = 0;
			mTotal = 0;
			mIndex = 0;
		}
		
		public void push(int length) {
			mNow = SystemClock.elapsedRealtime();
			if (mCount>0) {
				mDelta += mNow - mOldNow;
				mTotal += length;
				if (mDelta>RESOLUTION) {
					mSum[mIndex] = mTotal;
					mTotal = 0;
					mElapsed[mIndex] = mDelta;
					mDelta = 0;
					mIndex++;
					if (mIndex>=mSize) mIndex = 0;
				}
			}
			mOldNow = mNow;
			mCount++;
		}
		
		public int average() {
			long delta = 0, sum = 0;
			for (int i=0;i<mSize;i++) {
				sum += mSum[i];
				delta += mElapsed[i];
			}
			Log.d(TAG, "Time elapsed: "+delta);
			return (int) (delta>0?8000*sum/delta:0);
		}
		
	}
	
	/** ������ȷ�����ݰ��������ʡ� */
	protected static class Statistics {

		public final static String TAG = "Statistics";
		
		private int count=500, c = 0;
		private float m = 0, q = 0;
		private long elapsed = 0;
		private long start = 0;
		private long duration = 0;
		private long period = 6000000000L;
		private boolean initoffset = false;

		public Statistics(int count, long period) {
			this.count = count;
			this.period = period*1000000L; 
		}
		
		public void push(long value) {
			duration += value;
			elapsed += value;
			if (elapsed>period) {
				elapsed = 0;
				long now = System.nanoTime();
				if (!initoffset || (now - start < 0)) {
					start = now;
					duration = 0;
					initoffset = true;
				}
				value -= (now - start) - duration;
				//Log.d(TAG, "sum1: "+duration/1000000+" sum2: "+(now-start)/1000000+" drift: "+((now-start)-duration)/1000000+" v: "+value/1000000);
			}
			if (c<40) {
				// ���Ǻ��Ե�һ��40����ֵ,��Ϊ���ǿ��ܲ���׼ȷ
				c++;
				m = value;
			} else {
				m = (m*q+value)/(q+1);
				if (q<count) q++;
			}
		}
		
		public long average() {
			long l = (long)m-2000000;
			return l>0 ? l : 0;
		}

	}

}
