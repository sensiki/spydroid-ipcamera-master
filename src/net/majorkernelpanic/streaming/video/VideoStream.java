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

package net.majorkernelpanic.streaming.video;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.Stream;
import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.hw.NV21Convertor;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;

/** 
 * ��Ҫֱ��ʹ������ࡣ
 */
public abstract class VideoStream extends MediaStream {

	protected final static String TAG = "VideoStream";

	protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
	protected VideoQuality mQuality = mRequestedQuality.clone(); 
	protected SurfaceHolder.Callback mSurfaceHolderCallback = null;
	protected SurfaceView mSurfaceView = null;
	protected SharedPreferences mSettings = null;
	protected int mVideoEncoder, mCameraId = 0;
	protected int mRequestedOrientation = 0, mOrientation = 0;
	protected Camera mCamera;
	protected Thread mCameraThread;
	protected Looper mCameraLooper;

	protected boolean mCameraOpenedManually = true;
	protected boolean mFlashEnabled = false;
	protected boolean mSurfaceReady = false;
	protected boolean mUnlocked = false;
	protected boolean mPreviewStarted = false;

	protected String mMimeType;
	protected String mEncoderName;
	protected int mEncoderColorFormat;
	protected int mCameraImageFormat;
	protected int mMaxFps = 0;	

	/** 
	 * ��Ҫֱ��ʹ������ࡣ
	 * ʹ��Ĭ��CAMERA_FACING_BACK��
	 */
	public VideoStream() {
		this(CameraInfo.CAMERA_FACING_BACK);
	}	

	/** 
	 * ��Ҫֱ��ʹ������ࡣ
	 * @param camera ������CameraInfo��CAMERA_FACING_BACK��CameraInfo.CAMERA_FACING_FRONT
	 */
	@SuppressLint("InlinedApi")
	public VideoStream(int camera) {
		super();
		setCamera(camera);
	}

	/**
	 * �������,�����ڲ�׽��Ƶ��
	 * ��������κ�ʱ��͵�������������ı��´���������Ч��
	 * @param camera ������CameraInfo��CAMERA_FACING_BACK��CameraInfo.CAMERA_FACING_FRONT
	 */
	public void setCamera(int camera) {
		CameraInfo cameraInfo = new CameraInfo();
		int numberOfCameras = Camera.getNumberOfCameras();
		for (int i=0;i<numberOfCameras;i++) {
			Camera.getCameraInfo(i, cameraInfo);
			if (cameraInfo.facing == camera) {
				mCameraId = i;
				break;
			}
		}
	}

	/**	���ֻ���ǰ�úͺ�������ͷ֮���л�
	 * ������� {@link #startPreview()} , Ԥ�����������ж�. 
	 * ������� {@link #start()} , �����������ж�.
	 * ������Ѿ��ڴ������ˣ��㲻Ӧ�ô����̵߳������������ 
	 * @throws IOException 
	 * @throws RuntimeException 
	 **/
	public void switchCamera() throws RuntimeException, IOException {
		if (Camera.getNumberOfCameras() == 1) throw new IllegalStateException("Phone only has one camera !");
		boolean streaming = mStreaming;
		boolean previewing = mCamera!=null && mCameraOpenedManually; 
		mCameraId = (mCameraId == CameraInfo.CAMERA_FACING_BACK) ? CameraInfo.CAMERA_FACING_FRONT : CameraInfo.CAMERA_FACING_BACK; 
		setCamera(mCameraId);
		stopPreview();
		mFlashEnabled = false;
		if (previewing) startPreview();
		if (streaming) start(); 
	}

	public int getCamera() {
		return mCameraId;
	}

	/**
	 * ����һ��Surface����ʾԤ����¼ý��(��Ƶ)��
	 * ��������κ�ʱ��͵�������������ı佫�����´ε���{@link #start()}ʱ��Ч��
	 */
	public synchronized void setSurfaceView(SurfaceView view) {
		mSurfaceView = view;
		if (mSurfaceHolderCallback != null && mSurfaceView != null && mSurfaceView.getHolder() != null) {
			mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
		}
		if (mSurfaceView.getHolder() != null) {
			mSurfaceHolderCallback = new Callback() {
				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
					mSurfaceReady = false;
					stopPreview();
					Log.d(TAG,"Surface destroyed !");
				}
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					mSurfaceReady = true;
				}
				@Override
				public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
					Log.d(TAG,"Surface Changed !");
				}
			};
			mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
			mSurfaceReady = true;
		}
	}

	/** ����ֻ���LED���رջ��ߴ����� */
	public synchronized void setFlashState(boolean state) {
		// �������Ѿ�����,��������Ӧ�ø���
		if (mCamera != null) {

			if (mStreaming && mMode == MODE_MEDIARECORDER_API) {
				lockCamera();
			}

			Parameters parameters = mCamera.getParameters();

			// ���ǲ����ֻ��Ƿ��������
			if (parameters.getFlashMode()==null) {
				// �绰û������ƻ�ѡ����������л������
				throw new RuntimeException("Can't turn the flash on !");
			} else {
				parameters.setFlashMode(state?Parameters.FLASH_MODE_TORCH:Parameters.FLASH_MODE_OFF);
				try {
					mCamera.setParameters(parameters);
					mFlashEnabled = state;
				} catch (RuntimeException e) {
					mFlashEnabled = false;
					throw new RuntimeException("Can't turn the flash on !");
				} finally {
					if (mStreaming && mMode == MODE_MEDIARECORDER_API) {
						unlockCamera();
					}
				}
			}
		} else {
			mFlashEnabled = state;
		}
	}

	/** �����������ƣ��л��ֻ�������� */
	public synchronized void toggleFlash() {
		setFlashState(!mFlashEnabled);
	}

	/** ָʾ�ֻ���������Ƿ�򿪡� */
	public boolean getFlashState() {
		return mFlashEnabled;
	}

	/** 
	 * Ԥ���ķ���
	 * @param orientation Ԥ���ķ���
	 */
	public void setPreviewOrientation(int orientation) {
		mRequestedOrientation = orientation;
	}
	
	
	/** 
	 * �����������á���������κ�ʱ��͵�������������´ε���{@link #configure()}ʱ�ı���Ч��
	 * Sets the configuration of the stream. You can call this method at any time 
	 * and changes will take effect next time you call {@link #configure()}.
	 * @param videoQuality Quality of the stream
	 */
	public void setVideoQuality(VideoQuality videoQuality) {
		mRequestedQuality = videoQuality.clone();
	}

	/** 
	 * ��������Ʒ�ʡ�  
	 */
	public VideoQuality getVideoQuality() {
		return mRequestedQuality;
	}

	/**
	 * ������{@link #getSessionDescription()}ʱһЩ������Ҫ�洢(SPS��PPS ����)��
	 * @param prefs ��ʹ��SharedPreferences����SPS��PPS������
	 */
	public void setPreferences(SharedPreferences prefs) {
		mSettings = prefs;
	}

	/**
	 * ��������֮ǰ����Ҫ���������{ @link # getSessionDescription()}Ӧ�����õ�����
	 */
	public synchronized void configure() throws IllegalStateException, IOException {
		super.configure();
		mOrientation = mRequestedOrientation;
	}	
	
	/**
	 * ��ʼ��
	 * ���{ @link # startPreview()}û�б�������Ҳ�����������ʾԤ��
	 */
	public synchronized void start() throws IllegalStateException, IOException {
		if (!mPreviewStarted) mCameraOpenedManually = false;
		super.start();
		Log.d(TAG,"Stream configuration: FPS: "+mQuality.framerate+" Width: "+mQuality.resX+" Height: "+mQuality.resY);
	}

	/** ������ */
	public synchronized void stop() {
		if (mCamera != null) {
			if (mMode == MODE_MEDIACODEC_API) {
				mCamera.setPreviewCallbackWithBuffer(null);
			}
			if (mMode == MODE_MEDIACODEC_API_2) {
				((SurfaceView)mSurfaceView).removeMediaCodecSurface();
			}
			super.stop();
			// ������Ҫ��������Ԥ��
			if (!mCameraOpenedManually) {
				destroyCamera();
			} else {
				try {
					startPreview();
				} catch (RuntimeException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public synchronized void startPreview() 
			throws CameraInUseException, 
			InvalidSurfaceException, 
			ConfNotSupportedException, 
			RuntimeException {
		
		mCameraOpenedManually = true;
		if (!mPreviewStarted) {
			createCamera();
			updateCamera();
			try {
				mCamera.startPreview();
				mPreviewStarted = true;
			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}
		}
	}

	/**
	 * ֹͣԤ����
	 */
	public synchronized void stopPreview() {
		mCameraOpenedManually = false;
		stop();
	}

	/**
	 * MediaRecorder��Ƶ������ɡ�
	 */
	protected void encodeWithMediaRecorder() throws IOException {

		Log.d(TAG,"Video encoded using the MediaRecorder API");

		// ������Ҫһ�����ص�socket��ʹ��������������ת��������
		createSockets();

		// �����Ҫ���´����
		destroyCamera();
		createCamera();

		//ʹ�����MediaRecorder֮ǰ������� 
		unlockCamera();

		try {
			mMediaRecorder = new MediaRecorder();
			mMediaRecorder.setCamera(mCamera);
			mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
			mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
			mMediaRecorder.setVideoEncoder(mVideoEncoder);
			mMediaRecorder.setPreviewDisplay(mSurfaceView.getHolder().getSurface());
			mMediaRecorder.setVideoSize(mRequestedQuality.resX,mRequestedQuality.resY);
			mMediaRecorder.setVideoFrameRate(mRequestedQuality.framerate);

			// ʵ�����ĵĴ�����������Ҫ��
			mMediaRecorder.setVideoEncodingBitRate((int)(mRequestedQuality.bitrate*0.8));

			// ����д����������һ������socket�������ļ�!		
			// ��һ��С�������������ݴ���������򵥵�ִ��
			// can then be manipulated at the other end of the socket
			mMediaRecorder.setOutputFile(mSender.getFileDescriptor());

			mMediaRecorder.prepare();
			mMediaRecorder.start();

		} catch (Exception e) {
			throw new ConfNotSupportedException(e.getMessage());
		}

		// �⽫����MPEG4ͷ�����һ��ʧ�����ǲ���stream�κζ�����
		InputStream is = mReceiver.getInputStream();
		try {
			byte buffer[] = new byte[4];
			// Skip all atoms preceding mdat atom
			while (!Thread.interrupted()) {
				while (is.read() != 'm');
				is.read(buffer,0,3);
				if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') break;
			}
		} catch (IOException e) {
			Log.e(TAG,"Couldn't skip mp4 header :/");
			stop();
			throw e;
		}

		// �ֳɰ���װ��һ��RTP��λ��,ͨ�����緢�͡�
		mPacketizer.setDestination(mDestination, mRtpPort, mRtcpPort);
		mPacketizer.setInputStream(mReceiver.getInputStream());
		mPacketizer.start();

		mStreaming = true;

	}


	/**
	 *ͨ��MediaCodec ��Ƶ���롣
	 */
	protected void encodeWithMediaCodec() throws RuntimeException, IOException {
		if (mMode == MODE_MEDIACODEC_API_2) {
			// Uses the method MediaCodec.createInputSurface to feed the encoder
			encodeWithMediaCodecMethod2();
		} else {
			// Uses dequeueInputBuffer to feed the encoder
			encodeWithMediaCodecMethod1();
		}
	}	

	/**
	 * ͨ��MediaCodec��Ƶ���롣
	 */
	@SuppressLint("NewApi")
	protected void encodeWithMediaCodecMethod1() throws RuntimeException, IOException {

		Log.d(TAG,"Video encoded using the MediaCodec API with a buffer");

		// �����Ҫ��������Ĳ���
		createCamera();
		updateCamera();

		// �����������֡����
		measureFramerate();

		// �����Ҫ����Ԥ��
		if (!mPreviewStarted) {
			try {
				mCamera.startPreview();
				mPreviewStarted = true;
			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}
		}

		EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);
		final NV21Convertor convertor = debugger.getNV21Convertor();

		mMediaCodec = MediaCodec.createByCodecName(debugger.getEncoderName());
		MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);	
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,debugger.getEncoderColorFormat());
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mMediaCodec.start();

		Camera.PreviewCallback callback = new Camera.PreviewCallback() {
			long now = System.nanoTime()/1000, oldnow = now, i=0;
			ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {
				oldnow = now;
				now = System.nanoTime()/1000;
				if (i++>3) {
					i = 0;
					//Log.d(TAG,"Measured: "+1000000L/(now-oldnow)+" fps.");
				}
				try {
					int bufferIndex = mMediaCodec.dequeueInputBuffer(500000);
					if (bufferIndex>=0) {
						inputBuffers[bufferIndex].clear();
						convertor.convert(data, inputBuffers[bufferIndex]);
						mMediaCodec.queueInputBuffer(bufferIndex, 0, inputBuffers[bufferIndex].position(), now, 0);
					} else {
						Log.e(TAG,"No buffer available !");
					}
				} finally {
					mCamera.addCallbackBuffer(data);
				}				
			}
		};

		for (int i=0;i<10;i++) mCamera.addCallbackBuffer(new byte[convertor.getBufferSize()]);
		mCamera.setPreviewCallbackWithBuffer(callback);

		// �ֳɰ���װ��һ��RTP��λ��,ͨ�����緢��
		mPacketizer.setDestination(mDestination, mRtpPort, mRtcpPort);
		mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
		mPacketizer.start();

		mStreaming = true;

	}

	/**
	 * ͨ��MediaCodec��Ƶ���롣
	 * �����������ǽ�ʹ��buffer-to-surface��ʽ
	 */
	@SuppressLint({ "InlinedApi", "NewApi" })	
	protected void encodeWithMediaCodecMethod2() throws RuntimeException, IOException {

		Log.d(TAG,"Video encoded using the MediaCodec API with a surface");

		// �����Ҫ��������Ĳ���
		createCamera();
		updateCamera();

		// �����������֡����
		measureFramerate();

		EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);

		mMediaCodec = MediaCodec.createByCodecName(debugger.getEncoderName());
		MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);	
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		Surface surface = mMediaCodec.createInputSurface();
		((SurfaceView)mSurfaceView).addMediaCodecSurface(surface);
		mMediaCodec.start();

		// ��װ��һ��RTP��λ��,�ֳɰ�ͨ�����緢��
		mPacketizer.setDestination(mDestination, mRtpPort, mRtcpPort);
		mPacketizer.setInputStream(new MediaCodecInputStream(mMediaCodec));
		mPacketizer.start();

		mStreaming = true;

	}

	/**
	 * ����һ��ʹ��SDP���������� 
	 * 	�������ֻ����{@link Stream#configure()}����á�
	 * @throws IllegalStateException ��{@link Stream#configure()}û�б�����ʱ�׳�.
	 */	
	public abstract String getSessionDescription() throws IllegalStateException;

	/**
	 * ��һ���µ�Looper thread�����,�Ա㲻�Ǵ����̵߳���Ԥ���ص�
	 * �����Looper thread�׳�һ���쳣,���ǰ����������̡߳�
	 * @throws RuntimeException �����һ��Ӧ�ó����Ѿ���ʹ��������ܷ�����
	 */
	private void openCamera() throws RuntimeException {
		final Semaphore lock = new Semaphore(0);
		final RuntimeException[] exception = new RuntimeException[1];
		mCameraThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Looper.prepare();
				mCameraLooper = Looper.myLooper();
				try {
					mCamera = Camera.open(mCameraId);
				} catch (RuntimeException e) {
					exception[0] = e;
				} finally {
					lock.release();
					Looper.loop();
				}
			}
		});
		mCameraThread.start();
		lock.acquireUninterruptibly();
		if (exception[0] != null) throw new CameraInUseException(exception[0].getMessage());
	}

	protected synchronized void createCamera() throws RuntimeException {
		if (mSurfaceView == null)
			throw new InvalidSurfaceException("Invalid surface !");
		if (mSurfaceView.getHolder() == null || !mSurfaceReady) 
			throw new InvalidSurfaceException("Invalid surface !");

		if (mCamera == null) {
			openCamera();
			mUnlocked = false;
			mCamera.setErrorCallback(new Camera.ErrorCallback() {
				@Override
				public void onError(int error, Camera camera) {
					//�ֻ�����ͼʹ��ǰ�����һЩý���������������
					// ����ص��Ƿ���Ե���Ϊʵ����ȡ�����ֻ�
					if (error == Camera.CAMERA_ERROR_SERVER_DIED) {
						// �����������,Ӧ�ó�������ͷ������ʵ����һ���µ�
						Log.e(TAG,"Media server died !");
						// ���ǲ�֪����ʲô�߳�ֹͣ��Ҫͬ��
						mCameraOpenedManually = false;
						stop();
					} else {
						Log.e(TAG,"Error unknown with the camera: "+error);
					}	
				}
			});

			try {

				// ����ֻ��������,���Ǹ���mFlashEnabled��/�ر���
				//�����ƻ�ֻʹ���������¼��setRecordingHint(true)��һ���ǳ��õ��Ż�
				Parameters parameters = mCamera.getParameters();
				if (parameters.getFlashMode()!=null) {
					parameters.setFlashMode(mFlashEnabled?Parameters.FLASH_MODE_TORCH:Parameters.FLASH_MODE_OFF);
				}
				parameters.setRecordingHint(true);
				mCamera.setParameters(parameters);
				mCamera.setDisplayOrientation(mOrientation);

				try {
					if (mMode == MODE_MEDIACODEC_API_2) {
						mSurfaceView.startGLThread();
						mCamera.setPreviewTexture(mSurfaceView.getSurfaceTexture());
					} else {
						mCamera.setPreviewDisplay(mSurfaceView.getHolder());
					}
				} catch (IOException e) {
					throw new InvalidSurfaceException("Invalid surface !");
				}

			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}

		}
	}

	protected synchronized void destroyCamera() {
		if (mCamera != null) {
			if (mStreaming) super.stop();
			lockCamera();
			mCamera.stopPreview();
			try {
				mCamera.release();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage()!=null?e.getMessage():"unknown error");
			}
			mCamera = null;
			mCameraLooper.quit();
			mUnlocked = false;
			mPreviewStarted = false;
		}	
	}

	protected synchronized void updateCamera() throws RuntimeException {
		if (mPreviewStarted) {
			mPreviewStarted = false;
			mCamera.stopPreview();
		}

		Parameters parameters = mCamera.getParameters();
		mQuality = VideoQuality.determineClosestSupportedResolution(parameters, mQuality);
		int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);
		parameters.setPreviewFormat(mCameraImageFormat);
		parameters.setPreviewSize(mQuality.resX, mQuality.resY);
		parameters.setPreviewFpsRange(max[0], max[1]);

		try {
			mCamera.setParameters(parameters);
			mCamera.setDisplayOrientation(mOrientation);
			mCamera.startPreview();
			mPreviewStarted = true;
		} catch (RuntimeException e) {
			destroyCamera();
			throw e;
		}
	}

	protected void lockCamera() {
		if (mUnlocked) {
			Log.d(TAG,"Locking camera");
			try {
				mCamera.reconnect();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage());
			}
			mUnlocked = false;
		}
	}

	protected void unlockCamera() {
		if (!mUnlocked) {
			Log.d(TAG,"Unlocking camera");
			try {	
				mCamera.unlock();
			} catch (Exception e) {
				Log.e(TAG,e.getMessage());
			}
			mUnlocked = true;
		}
	}


	/**
	 * ������һ��Ԥ���ص�����ƽ��֡��
	 * ���ǽ�ʹ�����MediaCodecƽ��֡���ʡ� 
	 * ����̵߳������������
	 */
	private void measureFramerate() {
		final Semaphore lock = new Semaphore(0);

		final Camera.PreviewCallback callback = new Camera.PreviewCallback() {
			int i = 0, t = 0;
			long now, oldnow, count = 0;
			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {
				i++;
				now = System.nanoTime()/1000;
				if (i>3) {
					t += now - oldnow;
					count++;
				}
				if (i>20) {
					mQuality.framerate = (int) (1000000/(t/count)+1);
					lock.release();
				}
				oldnow = now;
			}
		};

		mCamera.setPreviewCallback(callback);

		try {
			lock.tryAcquire(2,TimeUnit.SECONDS);
			Log.d(TAG,"Actual framerate: "+mQuality.framerate);
			if (mSettings != null) {
				Editor editor = mSettings.edit();
				editor.putInt(PREF_PREFIX+"fps"+mRequestedQuality.framerate+","+mCameraImageFormat+","+mRequestedQuality.resX+mRequestedQuality.resY, mQuality.framerate);
				editor.commit();
			}
		} catch (InterruptedException e) {}

		mCamera.setPreviewCallback(null);

	}	

}
