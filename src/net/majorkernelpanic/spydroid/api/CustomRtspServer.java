package net.majorkernelpanic.spydroid.api;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.rtsp.RtspServer;

public class CustomRtspServer extends RtspServer {
	public CustomRtspServer() {
		super();
		// RTSP服务器默认禁用
		mEnabled = false;
	}
}

