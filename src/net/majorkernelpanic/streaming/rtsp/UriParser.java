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

package net.majorkernelpanic.streaming.rtsp;

import static net.majorkernelpanic.streaming.SessionBuilder.AUDIO_AAC;
import static net.majorkernelpanic.streaming.SessionBuilder.AUDIO_AMRNB;
import static net.majorkernelpanic.streaming.SessionBuilder.AUDIO_NONE;
import static net.majorkernelpanic.streaming.SessionBuilder.VIDEO_H263;
import static net.majorkernelpanic.streaming.SessionBuilder.VIDEO_H264;
import static net.majorkernelpanic.streaming.SessionBuilder.VIDEO_NONE;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.List;

import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.video.VideoQuality;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import android.hardware.Camera.CameraInfo;

/**
 * 这个类通过RTSP服务器和配置相应的会话解析收到的uri。
 */
public class UriParser {

	public final static String TAG = "UriParser";
	
	/**
	 * 根据给定的URI配置一个会话
	 * 这里有一些例子的uri可以用于配置会话:
	 * <ul><li>rtsp://xxx.xxx.xxx.xxx:8086?h264&flash=on</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?h263&camera=front&flash=on</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?h264=200-20-320-240</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?aac</li></ul>
	 * @param uri The URI
	 * @throws IllegalStateException
	 * @throws IOException
	 * @return A Session configured according to the URI
	 */
	public static Session parse(String uri) throws IllegalStateException, IOException {		
		SessionBuilder builder = SessionBuilder.getInstance().clone();
		byte audioApi = 0, videoApi = 0;

		URI aaa=URI.create(uri);
		
		List<NameValuePair> nameValuePairs  = URLEncodedUtils.parse(URI.create(uri),"UTF-8");
		if (nameValuePairs .size()>0) {

			builder.setAudioEncoder(AUDIO_NONE).setVideoEncoder(VIDEO_NONE);

			// 这些参数必须首先解析,否则他们不一定会考虑进去
			for (Iterator<NameValuePair> it = nameValuePairs .iterator();it.hasNext();) {
				NameValuePair param = it.next();

				// FLASH ON/OFF
				if (param.getName().equalsIgnoreCase("flash")) {
					if (param.getValue().equalsIgnoreCase("on")) 
						builder.setFlashEnabled(true);
					else 
						builder.setFlashEnabled(false);
				}

				// CAMERA -> 客户端可以选择前置摄像头和后面对镜头
				else if (param.getName().equalsIgnoreCase("camera")) {
					if (param.getValue().equalsIgnoreCase("back")) 
						builder.setCamera(CameraInfo.CAMERA_FACING_BACK);
					else if (param.getValue().equalsIgnoreCase("front")) 
						builder.setCamera(CameraInfo.CAMERA_FACING_FRONT);
				}

				// MULTICAST -> 流将被送到一个多播组
				// 默认mutlicast地址是228.5.6.7,但是客户端可以指定另一个
				else if (param.getName().equalsIgnoreCase("multicast")) {
					if (param.getValue()!=null) {
						try {
							InetAddress addr = InetAddress.getByName(param.getValue());
							if (!addr.isMulticastAddress()) {
								throw new IllegalStateException("Invalid multicast address !");
							}
							builder.setDestination(param.getValue());
						} catch (UnknownHostException e) {
							throw new IllegalStateException("Invalid multicast address !");
						}
					}
					else {
						// 默认多播地址
						builder.setDestination("228.5.6.7");
					}
				}

				// UNICAST -> 客户端可以使用这个来指定想要发送的流
				else if (param.getName().equalsIgnoreCase("unicast")) {
					if (param.getValue()!=null) {
						builder.setDestination(param.getValue());
					}					
				}
				
				// VIDEOAPI -> 可以被用来指定哪一个api将用于视频编码(MediaRecorder api或MediaCodec api)
				else if (param.getName().equalsIgnoreCase("videoapi")) {
					if (param.getValue()!=null) {
						if (param.getValue().equalsIgnoreCase("mr")) {
							videoApi = MediaStream.MODE_MEDIARECORDER_API;
						} else if (param.getValue().equalsIgnoreCase("mc")) {
							videoApi = MediaStream.MODE_MEDIACODEC_API;
						}
					}					
				}
				
				// AUDIOAPI -> 可以被用来指定哪一个api将用于音频编码(MediaRecorder api或MediaCodec api)
				else if (param.getName().equalsIgnoreCase("audioapi")) {
					if (param.getValue()!=null) {
						if (param.getValue().equalsIgnoreCase("mr")) {
							audioApi = MediaStream.MODE_MEDIARECORDER_API;
						} else if (param.getValue().equalsIgnoreCase("mc")) {
							audioApi = MediaStream.MODE_MEDIACODEC_API;
						}
					}					
				}		

				// TTL -> 客户端可以修改数据包的时间存活
				// 默认 ttl=64
				else if (param.getName().equalsIgnoreCase("ttl")) {
					if (param.getValue()!=null) {
						try {
							int ttl = Integer.parseInt(param.getValue());
							if (ttl<0) throw new IllegalStateException();
							builder.setTimeToLive(ttl);
						} catch (Exception e) {
							throw new IllegalStateException("The TTL must be a positive integer !");
						}
					}
				}

				// H.264
				else if (param.getName().equalsIgnoreCase("h264")) {
					VideoQuality quality = VideoQuality.parseQuality(param.getValue());
					builder.setVideoQuality(quality).setVideoEncoder(VIDEO_H264);
				}

				// H.263
				else if (param.getName().equalsIgnoreCase("h263")) {
					VideoQuality quality = VideoQuality.parseQuality(param.getValue());
					builder.setVideoQuality(quality).setVideoEncoder(VIDEO_H263);
				}

				// AMR
				else if (param.getName().equalsIgnoreCase("amrnb") || param.getName().equalsIgnoreCase("amr")) {
					AudioQuality quality = AudioQuality.parseQuality(param.getValue());
					builder.setAudioQuality(quality).setAudioEncoder(AUDIO_AMRNB);
				}

				// AAC
				else if (param.getName().equalsIgnoreCase("aac")) {
					AudioQuality quality = AudioQuality.parseQuality(param.getValue());
					builder.setAudioQuality(quality).setAudioEncoder(AUDIO_AAC);
				}

			}

		}

		if (builder.getVideoEncoder()==VIDEO_NONE && builder.getAudioEncoder()==AUDIO_NONE) {
			SessionBuilder b = SessionBuilder.getInstance();
			builder.setVideoEncoder(b.getVideoEncoder());
			builder.setAudioEncoder(b.getAudioEncoder());
		}

		Session session = builder.build();
		
		
		if (videoApi>0 && session.getVideoTrack() != null) {
			session.getVideoTrack().setStreamingMethod(videoApi);
		}
		
		
		if (audioApi>0 && session.getAudioTrack() != null) {
			session.getAudioTrack().setStreamingMethod(audioApi);
		}
		
		return session;

	}

}
