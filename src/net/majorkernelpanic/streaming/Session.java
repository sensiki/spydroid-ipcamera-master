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

package net.majorkernelpanic.streaming;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;

import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.audio.AudioStream;
import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.exceptions.StorageUnavailableException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtsp.RtspClient;
import net.majorkernelpanic.streaming.video.VideoQuality;
import net.majorkernelpanic.streaming.video.VideoStream;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * 你应该实例化这个类的{ @link SessionBuilder }。
 * 这是你想使用的类，同样使用RTP来视频或者音频流
 * 它拥有并提供一个{ @link VideoStream }和{ @link AudioStream }
 * syncronous和asyncrounous函数来启动和停止这些流。
 * 你应该实现一个回调接口{@link Callback}接收通知和错误报告。<br />
 * 
 * 如果你想传输流到流媒体服务器,你需要这个类的一个实例并把它交给{ @link RtspClient }。
 * 
 * 如果你不使用RTSP协议,你仍然需要向接收方发送会话描述为他能够解码音频/视频流。
 * 您可以获得这个会话描述通过调用{ @link #配置()}或{ @link # syncConfigure()}配置参数的会话
 * (audio samplingrate, video resolution),然后{@link Session#getSessionDescription()}。< br / >
 * 
 * See the example 2 here: https://github.com/fyhertz/libstreaming-examples to 
 * see an example of how to get a SDP.<br />
 * 
 * See the example 3 here: https://github.com/fyhertz/libstreaming-examples to 
 * see an example of how to stream to a RTSP server.<br />
 * 
 */
public class Session {

	public final static String TAG = "Session";

	public final static int STREAM_VIDEO = 0x01;

	public final static int STREAM_AUDIO = 0x00;

	/** 有app已经使用了了摄像头 (Camera.open() 失败). */
	public final static int ERROR_CAMERA_ALREADY_IN_USE = 0x00;

	/** 手机可能不支持您使用的一些流参数 (bit rate, frame rate...s). */
	public final static int ERROR_CONFIGURATION_NOT_SUPPORTED = 0x01;

	/** 
	 * 手机的内部存储没有准备好。
	 * Libstreaming试图在sdcard存储一个测试文件,但不能存。
	 * 查看H264Stream AACStream找出libstreaming为什么要这样。
	 */
	public final static int ERROR_STORAGE_NOT_READY = 0x02;

	/** 手机没有闪光灯。 */
	public final static int ERROR_CAMERA_HAS_NO_FLASH = 0x03;

	/** 提供的SurfaceView不是一个有效的表面,或尚未创建。 */
	public final static int ERROR_INVALID_SURFACE = 0x04;

	/** 
	 * 目标设置与{@link Session#setDestination(String)}不可能得到解决。
	 *可能意味着手机没有上网,或DNS服务器无法解析主机名
	 */
	public final static int ERROR_UNKNOWN_HOST = 0x05;

	/**
	 * 其他一些错误发生!
	 */
	public final static int ERROR_OTHER = 0x06;

	private String mOrigin;
	private String mDestination;
	private int mTimeToLive = 64;
	private long mTimestamp;

	private AudioStream mAudioStream = null;
	private VideoStream mVideoStream = null;

	private Callback mCallback;
	private Handler mMainHandler;
	
	private static CountDownLatch sSignal;
	private static Handler sHandler;

	static {
		// 在调用会话的异步方法时将使用这个创建线程。
		sSignal = new CountDownLatch(1);
		new HandlerThread("net.majorkernelpanic.streaming.Session"){
			@Override
			protected void onLooperPrepared() {
				sHandler = new Handler();
				sSignal.countDown();
			}
		}.start();
	}
	
	/** 
	 * 可以通过添加追踪自定义创建一个流媒体会话。
	 */
	public Session() {
		long uptime = System.currentTimeMillis();
		mMainHandler = new Handler(Looper.getMainLooper());
		mTimestamp = (uptime/1000)<<32 & (((uptime-((uptime/1000)*1000))>>32)/1000); // NTP timestamp
		mOrigin = "127.0.0.1";
		
		// 我确保我们不会给一个现有的线程发送Runnables
		try {
			sSignal.await();
		} catch (InterruptedException e) {}
	}

	/**
	 * The callback interface you need to implement to get some feedback
	 * Those will be called from the UI thread.
	 */
	public interface Callback {

		/** 
		 * Called periodically to inform you on the bandwidth 
		 * consumption of the streams when streaming. 
		 */
		public void onBitrareUpdate(long bitrate);

		/** Called when some error occurs. */
		public void onSessionError(int reason, int streamType, Exception e);

		/** 
		 * Called when the previw of the {@link VideoStream}
		 * has correctly been started.
		 * If an error occurs while starting the preview,
		 * {@link Callback#onSessionError(int, int, Exception)} will be
		 * called instead of {@link Callback#onPreviewStarted()}.
		 */
		public void onPreviewStarted();

		/** 
		 * Called when the session has correctly been configured 
		 * after calling {@link Session#configure()}.
		 * If an error occurs while configuring the {@link Session},
		 * {@link Callback#onSessionError(int, int, Exception)} will be
		 * called instead of  {@link Callback#onSessionConfigured()}.
		 */
		public void onSessionConfigured();

		/** 
		 * Called when the streams of the session have correctly been started.
		 * If an error occurs while starting the {@link Session},
		 * {@link Callback#onSessionError(int, int, Exception)} will be
		 * called instead of  {@link Callback#onSessionStarted()}. 
		 */
		public void onSessionStarted();

		/** Called when the stream of the session have been stopped. */
		public void onSessionStopped();

	}

	/** You probably don't need to use that directly, use the {@link SessionBuilder}. */
	void addAudioTrack(AudioStream track) {
		removeAudioTrack();
		mAudioStream = track;
	}

	/** You probably don't need to use that directly, use the {@link SessionBuilder}. */
	void addVideoTrack(VideoStream track) {
		removeVideoTrack();
		mVideoStream = track;
	}

	/** You probably don't need to use that directly, use the {@link SessionBuilder}. */
	void removeAudioTrack() {
		if (mAudioStream != null) {
			mAudioStream.stop();
			mAudioStream = null;
		}
	}

	/** You probably don't need to use that directly, use the {@link SessionBuilder}. */
	void removeVideoTrack() {
		if (mVideoStream != null) {
			mVideoStream.stopPreview();
			mVideoStream = null;
		}
	}

	/** Returns the underlying {@link AudioStream} used by the {@link Session}. */
	public AudioStream getAudioTrack() {
		return mAudioStream;
	}

	/** Returns the underlying {@link VideoStream} used by the {@link Session}. */
	public VideoStream getVideoTrack() {
		return mVideoStream;
	}	

	/**
	 * Sets the callback interface that will be called by the {@link Session}.
	 * @param callback The implementation of the {@link Callback} interface
	 */
	public void setCallback(Callback callback) {
		mCallback = callback;
	}	

	/** 
	 * The origin address of the session.
	 * It appears in the sessionn description.
	 * @param origin The origin address
	 */
	public void setOrigin(String origin) {
		mOrigin = origin;
	}	

	/** 
	 * The destination address for all the streams of the session.
	 * Changes will be taken into account the next time you start the session.
	 * @param destination The destination address
	 */
	public void setDestination(String destination) {
		mDestination =  destination;
	}

	/** 
	 * Set the TTL of all packets sent during the session.
	 * Changes will be taken into account the next time you start the session.
	 * @param ttl The Time To Live
	 */
	public void setTimeToLive(int ttl) {
		mTimeToLive = ttl;
	}

	/** 
	 * Sets the configuration of the stream. You can call this method at any time 
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param quality Quality of the stream
	 */
	public void setVideoQuality(VideoQuality quality) {
		if (mVideoStream != null) {
			mVideoStream.setVideoQuality(quality);
		}
	}

	/**
	 * Sets a Surface to show a preview of recorded media (video). 
	 * You can call this method at any time and changes will take effect next time you call {@link #start()} or {@link #startPreview()}.
	 */
	public void setSurfaceView(final SurfaceView view) {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoStream != null) {
					mVideoStream.setSurfaceView(view);
				}
			}				
		});
	}

	/** 
	 * Sets the orientation of the preview. You can call this method at any time 
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param orientation The orientation of the preview
	 */
	public void setPreviewOrientation(int orientation) {
		if (mVideoStream != null) {
			mVideoStream.setPreviewOrientation(orientation);
		}
	}	
	
	/** 
	 * Sets the configuration of the stream. You can call this method at any time 
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param quality Quality of the stream
	 */
	public void setAudioQuality(AudioQuality quality) {
		if (mAudioStream != null) {
			mAudioStream.setAudioQuality(quality);
		}
	}

	/**
	 * Returns the {@link Callback} interface that was set with 
	 * {@link #setCallback(Callback)} or null if none was set.
	 */
	public Callback getCallback() {
		return mCallback;
	}	

	/** 
	 * Returns a Session Description that can be stored in a file or sent to a client with RTSP.
	 * @return The Session Description.
	 * @throws IllegalStateException Thrown when {@link #setDestination(String)} has never been called.
	 */
	public String getSessionDescription() {
		StringBuilder sessionDescription = new StringBuilder();
		if (mDestination==null) {
			throw new IllegalStateException("setDestination() has not been called !");
		}
		sessionDescription.append("v=0\r\n");
		// TODO: Add IPV6 support
		sessionDescription.append("o=- "+mTimestamp+" "+mTimestamp+" IN IP4 "+mOrigin+"\r\n");
		sessionDescription.append("s=Unnamed\r\n");
		sessionDescription.append("i=N/A\r\n");
		sessionDescription.append("c=IN IP4 "+mDestination+"\r\n");
		// t=0 0 means the session is permanent (we don't know when it will stop)
		sessionDescription.append("t=0 0\r\n");
		sessionDescription.append("a=recvonly\r\n");
		// Prevents two different sessions from using the same peripheral at the same time
		if (mAudioStream != null) {
			sessionDescription.append(mAudioStream.getSessionDescription());
			sessionDescription.append("a=control:trackID="+0+"\r\n");
		}
		if (mVideoStream != null) {
			sessionDescription.append(mVideoStream.getSessionDescription());
			sessionDescription.append("a=control:trackID="+1+"\r\n");
		}			
		return sessionDescription.toString();
	}

	/** Returns the destination set with {@link #setDestination(String)}. */
	public String getDestination() {
		return mDestination;
	}

	/** Returns an approximation of the bandwidth consumed by the session in bit per seconde. */
	public long getBitrate() {
		long sum = 0;
		if (mAudioStream != null) sum += mAudioStream.getBitrate();
		if (mVideoStream != null) sum += mVideoStream.getBitrate();
		return sum;
	}

	/** Indicates if a track is currently running. */
	public boolean isStreaming() {
		if ( (mAudioStream!=null && mAudioStream.isStreaming()) || (mVideoStream!=null && mVideoStream.isStreaming()) )
			return true;
		else 
			return false;
	}

	/** 
	 * Configures all streams of the session.
	 **/
	public void configure() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					syncConfigure();
				} catch (Exception e) {};
			}
		});
	}	

	/** 
	 * Does the same thing as {@link #configure()}, but in a syncronous manner.
	 * Throws exceptions in addition to calling a callback 
	 * {@link Callback#onSessionError(int, int, Exception)} when
	 * an error occurs.	
	 **/
	public void syncConfigure()  
			throws CameraInUseException, 
			StorageUnavailableException,
			ConfNotSupportedException, 
			InvalidSurfaceException, 
			RuntimeException,
			IOException {

		for (int id=0;id<2;id++) {
			Stream stream = id==0 ? mAudioStream : mVideoStream;
			if (stream!=null && !stream.isStreaming()) {
				try {
					stream.configure();
				} catch (CameraInUseException e) {
					postError(ERROR_CAMERA_ALREADY_IN_USE , id, e);
					throw e;
				} catch (StorageUnavailableException e) {
					postError(ERROR_STORAGE_NOT_READY , id, e);
					throw e;
				} catch (ConfNotSupportedException e) {
					postError(ERROR_CONFIGURATION_NOT_SUPPORTED , id, e);
					throw e;
				} catch (InvalidSurfaceException e) {
					postError(ERROR_INVALID_SURFACE , id, e);
					throw e;
				} catch (IOException e) {
					postError(ERROR_OTHER, id, e);
					throw e;
				} catch (RuntimeException e) {
					postError(ERROR_OTHER, id, e);
					throw e;
				}
			}
		}
		postSessionConfigured();
	}

	/** 
	 * Asyncronously starts all streams of the session.
	 **/
	public void start() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					syncStart();
				} catch (Exception e) {}
			}				
		});
	}

	/** 
	 * Starts a stream in a syncronous manner. 
	 * Throws exceptions in addition to calling a callback.
	 * @param id The id of the stream to start
	 **/
	public void syncStart(int id) 			
			throws CameraInUseException, 
			StorageUnavailableException,
			ConfNotSupportedException, 
			InvalidSurfaceException, 
			UnknownHostException,
			IOException {
		
		Stream stream = id==0 ? mAudioStream : mVideoStream;
		if (stream!=null && !stream.isStreaming()) {
			try {
				InetAddress destination =  InetAddress.getByName(mDestination);
				stream.setTimeToLive(mTimeToLive);
				stream.setDestinationAddress(destination);
				stream.start();
				if (getTrack(1-id) == null || getTrack(1-id).isStreaming()) {
					postSessionStarted();
				}
				if (getTrack(1-id) == null || !getTrack(1-id).isStreaming()) {
					sHandler.post(mUpdateBitrate);
				}
			} catch (UnknownHostException e) {
				postError(ERROR_UNKNOWN_HOST, id, e);
				throw e;
			} catch (CameraInUseException e) {
				postError(ERROR_CAMERA_ALREADY_IN_USE , id, e);
				throw e;
			} catch (StorageUnavailableException e) {
				postError(ERROR_STORAGE_NOT_READY , id, e);
				throw e;
			} catch (ConfNotSupportedException e) {
				postError(ERROR_CONFIGURATION_NOT_SUPPORTED , id, e);
				throw e;
			} catch (InvalidSurfaceException e) {
				postError(ERROR_INVALID_SURFACE , id, e);
				throw e;
			} catch (IOException e) {
				postError(ERROR_OTHER, id, e);
				throw e;
			} catch (RuntimeException e) {
				postError(ERROR_OTHER, id, e);
				throw e;
			}
		}

	}	

	/** 
	 * Does the same thing as {@link #start()}, but in a syncronous manner. 
	 * Throws exceptions in addition to calling a callback.
	 **/
	public void syncStart() 			
			throws CameraInUseException, 
			StorageUnavailableException,
			ConfNotSupportedException, 
			InvalidSurfaceException, 
			UnknownHostException,
			IOException {

		syncStart(1);
		try {
			syncStart(0);
		} catch (RuntimeException e) {
			syncStop(1);
			throw e;
		} catch (IOException e) {
			syncStop(1);
			throw e;
		}

	}	

	/** Stops all existing streams. */
	public void stop() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				syncStop();
			}
		});
	}

	/** 
	 * Stops one stream in a syncronous manner.
	 * @param id The id of the stream to stop
	 **/	
	private void syncStop(final int id) {
		Stream stream = id==0 ? mAudioStream : mVideoStream;
		if (stream!=null) {
			stream.stop();
		}
	}		
	
	/** Stops all existing streams in a syncronous manner. */
	public void syncStop() {
		syncStop(0);
		syncStop(1);
		postSessionStopped();
	}	

	public void startPreview() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoStream != null) {
					try {
						mVideoStream.configure();
						mVideoStream.startPreview();
						postPreviewStarted();
					} catch (CameraInUseException e) {
						postError(ERROR_CAMERA_ALREADY_IN_USE , STREAM_VIDEO, e);
					} catch (ConfNotSupportedException e) {
						postError(ERROR_CONFIGURATION_NOT_SUPPORTED , STREAM_VIDEO, e);
					} catch (InvalidSurfaceException e) {
						postError(ERROR_INVALID_SURFACE , STREAM_VIDEO, e);
					} catch (RuntimeException e) {
						postError(ERROR_OTHER, STREAM_VIDEO, e);
					} catch (StorageUnavailableException e) {
						postError(ERROR_STORAGE_NOT_READY, STREAM_VIDEO, e);
					} catch (IOException e) {
						postError(ERROR_OTHER, STREAM_VIDEO, e);
					}
				}
			}
		});
	}

	public void stopPreview() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoStream != null) {
					mVideoStream.stopPreview();
				}
			}
		});
	}	

	public void switchCamera() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoStream != null) {
					try {
						mVideoStream.switchCamera();
						postPreviewStarted();
					} catch (CameraInUseException e) {
						postError(ERROR_CAMERA_ALREADY_IN_USE , STREAM_VIDEO, e);
					} catch (ConfNotSupportedException e) {
						postError(ERROR_CONFIGURATION_NOT_SUPPORTED , STREAM_VIDEO, e);
					} catch (InvalidSurfaceException e) {
						postError(ERROR_INVALID_SURFACE , STREAM_VIDEO, e);
					} catch (IOException e) {
						postError(ERROR_OTHER, STREAM_VIDEO, e);
					} catch (RuntimeException e) {
						postError(ERROR_OTHER, STREAM_VIDEO, e);
					}
				}
			}
		});
	}

	public int getCamera() {
		return mVideoStream != null ? mVideoStream.getCamera() : 0;

	}

	public void toggleFlash() {
		sHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoStream != null) {
					try {
						mVideoStream.toggleFlash();
					} catch (RuntimeException e) {
						postError(ERROR_CAMERA_HAS_NO_FLASH, STREAM_VIDEO, e);
					}
				}
			}
		});
	}	

	/** Deletes all existing tracks & release associated resources. */
	public void release() {
		removeAudioTrack();
		removeVideoTrack();
		sHandler.getLooper().quit();
	}

	private void postPreviewStarted() {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mCallback != null) {
					mCallback.onPreviewStarted(); 
				}
			}
		});
	}

	private void postSessionConfigured() {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mCallback != null) {
					mCallback.onSessionConfigured(); 
				}
			}
		});
	}

	private void postSessionStarted() {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mCallback != null) {
					mCallback.onSessionStarted(); 
				}
			}
		});
	}		

	private void postSessionStopped() {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mCallback != null) {
					mCallback.onSessionStopped(); 
				}
			}
		});
	}	

	private void postError(final int reason, final int streamType,final Exception e) {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mCallback != null) {
					mCallback.onSessionError(reason, streamType, e); 
				}
			}
		});
	}	

	private void postBitRate(final long bitrate) {
		mMainHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mCallback != null) {
					mCallback.onBitrareUpdate(bitrate);
				}
			}
		});
	}		

	private Runnable mUpdateBitrate = new Runnable() {
		@Override
		public void run() {
			if (isStreaming()) { 
				postBitRate(getBitrate());
				sHandler.postDelayed(mUpdateBitrate, 500);
			} else {
				postBitRate(0);
			}
		}
	};

	//判断频道是否存在
	//0表示音频 1表示视频
	public boolean trackExists(int id) {
		if (id==0) 
			return mAudioStream!=null;
		else
			return mVideoStream!=null;
	}

	//对视频流和音频流的返回 
	public Stream getTrack(int id) {
		if (id==0)
			return mAudioStream;
		else
			return mVideoStream;
	}

}
