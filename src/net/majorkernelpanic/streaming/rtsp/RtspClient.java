/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 *
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.Stream;
import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.exceptions.StorageUnavailableException;
import net.majorkernelpanic.streaming.rtsp.RtspServer.CallbackListener;
import net.majorkernelpanic.streaming.rtsp.RtspServer.LocalBinder;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * RFC 2326.
 * һ���������첽��������
 * ������ԭʼĿ����ʵ��һ��СRTSP�ͻ�����Wowza���ݡ�
 * ������RFC 2069ʵ����Digest Access Authentication��
 */
public class RtspClient extends Service
{

    public final static String TAG = "RtspClient";

    /** ������RTSP������ʧ��ʱ������Ϣ��*/
    public final static int ERROR_CONNECTION_FAILED = 0x01;

    /** ��֤����ʱ������Ϣ�� */
    public final static int ERROR_WRONG_CREDENTIALS = 0x03;

    /**
     * ��RTSP����������������ĳ��ԭ��ʧʱ������Ϣ(����,the user is going under a bridge)��
     * ʧȥ�������������ʱ,ֻҪ{ @link # stopStream()}�����ã��ͻ��˻��Զ��������ӡ�
     **/
    public final static int ERROR_CONNECTION_LOST = 0x04;

    /**
     * ��RTSP�����������Ѿ��ָ�ʱ������Ϣ��
     * ʧȥ�������������ʱ,ֻҪ{ @link # stopStream()}�����ã��ͻ��˻��Զ��������ӡ�
     */
    public final static int MESSAGE_CONNECTION_RECOVERED = 0x05;

    private final static int STATE_STARTED = 0x00;
    private final static int STATE_STARTING = 0x01;
    private final static int STATE_STOPPING = 0x02;
    private final static int STATE_STOPPED = 0x03;
    private int mState = 0;

    /** �˿��Ѿ���ʹ��. */
    public final static int ERROR_BIND_FAILED = 0x00;
    /** ����ʼ�� */
    public final static int MESSAGE_STREAMING_STARTED = 0X00;

    /** �������� */
    public final static int MESSAGE_STREAMING_STOPPED = 0X01;

    protected SharedPreferences mSharedPreferences;
    protected boolean mEnabled = false;
    protected WeakHashMap<Session, Object> mSessions = new WeakHashMap<Session, Object>(2);

    private class Parameters
    {
        public String host;
        public String username="sen";
        public String password="123";
        public String path;
        public Session session;
        public int port;

        public Parameters clone()
        {
            Parameters params = new Parameters();
            params.host = host;
            params.username = username;
            params.password = password;
            params.path = path;
            params.session = session;
            params.port = port;
            return params;
        }
    }


    private Parameters mTmpParameters;
    private Parameters mParameters;

    private Socket mSocket;
    private String mSessionID;
    private String mAuthorization;
    private BufferedReader mBufferedReader;
    private OutputStream mOutputStream;
    private int mCSeq;
    private Callback mCallback;
    private Handler mMainHandler;
    private Handler mHandler;

    private final IBinder mBinder = new LocalBinder();
    private boolean mRestart = false;
    private final LinkedList<CallbackListener> mListeners = new LinkedList<CallbackListener>();

    public final static String KEY_SERVER_ENABLED = "server_enabled";
    public final static String KEY_SERVER_HOST = "server_addr";
    public final static String KEY_SERVER_PORT = "server_port";
    public final static String KEY_SERVER_PATH = "server_id";



    public RtspClient()
    {
        mCSeq = 0;
        mTmpParameters = new Parameters();
        mTmpParameters.host = "192.168.100.192";
        mTmpParameters.port = 554;
        mTmpParameters.path = "/aaa.sdp";
        mAuthorization = null;
        mCallback = null;
        mMainHandler = new Handler(Looper.getMainLooper());
        mState = STATE_STOPPED;

        final Semaphore signal = new Semaphore(0);
        new HandlerThread("net.majorkernelpanic.streaming.RtspClient")
        {
            @Override
            protected void onLooperPrepared()
            {
                mHandler = new Handler();
                signal.release();
            }
        } .start();
        signal.acquireUninterruptibly();

    }

    /**
     * ����Ҫʵ��֪���ص��ӿ���RTSP������������ʲô��(����Wowzaý�������)��
     */
    public interface Callback
    {
        public void onRtspUpdate(int message, Exception exception);
    }
    /**
     * ���ûص��ӿ�,��������RTSP���ӵ�״̬������RTSP�����������ӡ�
     * @param cb The implementation of the {@link Callback} interface
     */
    public void setCallback(Callback cb)
    {
        mCallback = cb;
    }

    /** ע��:�ص�������һ�����ui�̵߳���!*/
    public interface CallbackListener
    {

        /** ��������ʱ���á� */
        void onError(RtspClient server, Exception e, int error);

        /**������/ֹͣʱ���á� */
        void onMessage(RtspClient server, int message);

    }

    /**
     * һ��������һ����������ͨ���鿴{@link RtspServer.CallbackListener}ȥ���ʲô�¼��������
     * @param listener The listener
     */
    public void addCallbackListener(CallbackListener listener)
    {
        synchronized (mListeners)
        {
            if (mListeners.size() > 0)
            {
                for (CallbackListener cl : mListeners)
                {
                    if (cl == listener) return;
                }
            }
            mListeners.add(listener);
        }
    }

    /**
     * ɾ����������
     * @param listener The listener
     */
    public void removeCallbackListener(CallbackListener listener)
    {
        synchronized (mListeners)
        {
            mListeners.remove(listener);
        }
    }

    /**
     * ���{@link Session}�����ڷ���������
     * �����{@link #startStream()}ǰû�е��ã�������������
     */
    public void setSession()
    {
        Session msession = new Session();
        msession = null;
        try
        {
            msession = UriParser.parse("rtsp://" + mTmpParameters.host + ":559");
        }
        catch (IllegalStateException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        catch (IOException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        msession.setOrigin("127.0.0.1");
        if (msession.getDestination() == null)
        {
            msession.setDestination("mTmpParameters.host");
        }
        try
        {
            msession.syncConfigure();
        }
        catch (CameraInUseException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        catch (StorageUnavailableException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        catch (ConfNotSupportedException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        catch (InvalidSurfaceException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        catch (RuntimeException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        catch (IOException e)
        {
            // TODO �Զ����ɵ� catch ��
            e.printStackTrace();
        }
        mTmpParameters.session = msession;
    }

    public Session getSession()
    {
        return mTmpParameters.session;
    }

    /**
     * ����RTSP��������Ŀ�ĵ�ַ��
     * @param host Ŀ�ĵ�ַ
     * @param port Ŀ�Ķ˿�
     */
    public void setServerAddress(String host, int port)
    {
        mTmpParameters.port = port;
        mTmpParameters.host = host;
    }

    /**
     * ����ڷ����������������֤������Ҫʹ����Ч���û���/��������������
     * ֻ�ܸ���RFC 2069ʵ��Access Authentication according
     * @param username �û���
     * @param password ����
     */
    public void setCredentials(String username, String password)
    {
        mTmpParameters.username = username;
        mTmpParameters.password = password;
    }

    /**
     * �����͵���·��
     * @param path ·��
     */
    public void setStreamPath(String path)
    {
        mTmpParameters.path = path;
    }

    public boolean isStreaming()
    {
        return mState == STATE_STARTED | mState == STATE_STARTING;
    }

    /**
     * ���ӵ�RTSP������������,������Ч�ؿ�ʼ��
     * ����Ҫ����{@link #setServerAddress(String, int)}��
     * �����ڵ���֮ǰ�����{@link #setSession(Session)} {@link #setCredentials(String, String)}
     * Ӧ�õ����߳�!
     */
    public void startStream()
    {
        if ((!mEnabled) || mRestart)
        {
            stopStream();
        }
        if (mEnabled)
        {
            if (mTmpParameters.host == null) throw new IllegalStateException("setServerAddress(String,int) has not been called !");
            setSession();
            if (mTmpParameters.session == null) throw new IllegalStateException("setSession() has not been called !");
            mHandler.post(new Runnable ()
            {
                @Override
                public void run()
                {
                    if (mState != STATE_STOPPED) return;
                    mState = STATE_STARTING;

                    Log.d(TAG, "Connecting to RTSP server...");

                    //����û�����һЩ���������ÿͻ���,�������޸�����Ϊ,ֱ��������
                    mParameters = mTmpParameters.clone();
                    mParameters.session.setDestination(mTmpParameters.host);

                    try
                    {
                        mParameters.session.syncConfigure();
                    }
                    catch (Exception e)
                    {
                        mParameters.session = null;
                        mState = STATE_STOPPED;
                        return;
                    }

                    try
                    {
                        tryConnection();
                    }
                    catch (Exception e)
                    {
                        postError(ERROR_CONNECTION_FAILED, e);
                        abord();
                        return;
                    }

                    try
                    {
                        mParameters.session.syncStart();
                        mState = STATE_STARTED;
                        mHandler.post(mConnectionMonitor);
                    }
                    catch (Exception e)
                    {
                        abord();
                    }

                }
            });
            mRestart = false;
        }
    }

    /**
     * ֹͣ��,��֪ͨRTSP��������
     */
    public void stopStream()
    {
        mHandler.post(new Runnable ()
        {
            @Override
            public void run()
            {
                if (mParameters != null && mParameters.session != null)
                {
                    mParameters.session.stop();
                }
                if (mState != STATE_STOPPED)
                {
                    mState = STATE_STOPPING;
                    abord();
                }
            }
        });
    }

    public void release()
    {
        stopStream();
        mHandler.getLooper().quit();
    }

    private void abord()
    {
        try
        {
            sendRequestTeardown();
        }
        catch (Exception ignore) {}
        try
        {
            mSocket.close();
        }
        catch (Exception ignore) {}
        mHandler.removeCallbacks(mConnectionMonitor);
        mHandler.removeCallbacks(mRetryConnection);
        mState = STATE_STOPPED;
    }

    private void tryConnection() throws IOException
    {
        mCSeq = 0;
        mSocket = new Socket(mParameters.host, mParameters.port);
        mBufferedReader = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));
        mOutputStream = mSocket.getOutputStream();
        sendRequestAnnounce();
        sendRequestSetup();
        sendRequestRecord();
    }

    @Override
    public void onCreate()
    {

        // �����ǻָ������״̬6
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mTmpParameters.port = Integer.parseInt(mSharedPreferences.getString(KEY_SERVER_PORT, String.valueOf(mTmpParameters.port)));
        mTmpParameters.host = mSharedPreferences.getString(KEY_SERVER_HOST, String.valueOf(mTmpParameters.host));
        mTmpParameters.path = mSharedPreferences.getString(KEY_SERVER_PATH, String.valueOf(mTmpParameters.path));
        mEnabled = mSharedPreferences.getBoolean(KEY_SERVER_ENABLED, mEnabled);

        // ��������޸�,�����������е���
        mSharedPreferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);

        //startStream();
    }

    @Override
    public void onDestroy()
    {
        stopStream();
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
    }

    private OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener = new OnSharedPreferenceChangeListener()
    {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
        {

            if (key.equals(KEY_SERVER_PORT))
            {
                int port = Integer.parseInt(sharedPreferences.getString(KEY_SERVER_PORT, String.valueOf(mTmpParameters.port)));
                if (port != mTmpParameters.port)
                {
                    mTmpParameters.port = port;
                    mRestart = true;

                }
            }
            if (key.equals(KEY_SERVER_HOST))
            {
                String host = sharedPreferences.getString(KEY_SERVER_HOST, String.valueOf(mTmpParameters.host));
                if (host != mTmpParameters.host)
                {
                    mTmpParameters.host = host;
                    mRestart = true;

                }
            }
            if (key.equals(KEY_SERVER_PATH))
            {
                String path = sharedPreferences.getString(KEY_SERVER_PATH, String.valueOf(mTmpParameters.path));
                if (path != mTmpParameters.path)
                {
                    mTmpParameters.path = path;
                    mRestart = true;

                }
            }
            else if (key.equals(KEY_SERVER_ENABLED))
            {
                mEnabled = sharedPreferences.getBoolean(KEY_SERVER_ENABLED, mEnabled);
                //startStream();
            }
            //if(mRestart)
                //startStream();

        }
    };

    /**
     * ��װ������ANNOUNCE��֪ͨ������
     * ����URLʶ�����ʾ��ý������������͸�������
     */
    private void sendRequestAnnounce() throws IllegalStateException, SocketException, IOException
    {

        String body = mParameters.session.getSessionDescription();
        String request = "ANNOUNCE rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path + " RTSP/1.0\r\n" +
                         "CSeq: " + (++mCSeq) + "\r\n" +
                         "User-Agent: sensiki\r\n" +
                         "Session: 23145686\r\n" +
                         "Content-Length: " + body.length() + "\r\n" +
                         "Content-Type: application/sdp \r\n\r\n" +
                         body;
        Log.i(TAG, request.substring(0, request.indexOf("\r\n")));

        mOutputStream.write(request.getBytes("UTF-8"));
        Response response = Response.parseResponse(mBufferedReader);

        if (response.headers.containsKey("server"))
        {
            Log.v(TAG, "RTSP server name:" + response.headers.get("server"));
        }
        else
        {
            Log.v(TAG, "RTSP server name unknown");
        }

        try
        {
            Log.v(TAG, "RTSP session name:" + response.headers.get("session"));
            Matcher m = Response.rexegSession.matcher(response.headers.get("session"));
            m.find();
            mSessionID = m.group(1);
        }
        catch (Exception e)
        {
            throw new IOException("Invalid response from server. Session id: " + mSessionID);
        }

        if (response.status == 401)
        {
            String nonce, realm;
            Matcher m;

            if (mParameters.username == null || mParameters.password == null) throw new IllegalStateException("Authentication is enabled and setCredentials(String,String) was not called !");

            try
            {
                m = Response.rexegAuthenticate.matcher(response.headers.get("www-authenticate"));
                m.find();
                nonce = m.group(2);
                realm = m.group(1);
            }
            catch (Exception e)
            {
                throw new IOException("Invalid response from server");
            }

            String uri = "rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path;
            String hash1 = computeMd5Hash(mParameters.username + ":" + m.group(1) + ":" + mParameters.password);
            String hash2 = computeMd5Hash("ANNOUNCE" + ":" + uri);
            String hash3 = computeMd5Hash(hash1 + ":" + m.group(2) + ":" + hash2);

            mAuthorization = "Digest username=\"" + mParameters.username + "\",realm=\"" + realm + "\",nonce=\"" + nonce + "\",uri=\"" + uri + "\",response=\"" + hash3 + "\"\r\n";

            request = "ANNOUNCE rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path + " RTSP/1.0\r\n" +
                      "CSeq: " + (++mCSeq) + "\r\n" +
                      "Content-Length: " + body.length() + "\r\n" +
                      "Authorization: " + mAuthorization +
                      "Session: " + mSessionID + "\r\n" +
                      "Content-Type: application/sdp \r\n\r\n" +
                      body;

            Log.i(TAG, request.substring(0, request.indexOf("\r\n")));

            mOutputStream.write(request.getBytes("UTF-8"));
            response = Response.parseResponse(mBufferedReader);

            if (response.status == 401) throw new RuntimeException("Bad credentials !");

        }
        else if (response.status == 403)
        {
            throw new RuntimeException("Access forbidden !");
        }

    }

    /**
     * ��װ������SETUP�����ã�����
     * �÷���������������Դ������RTSP���ӡ�
     */
    private void sendRequestSetup() throws IllegalStateException, SocketException, IOException
    {
        for (int i = 0; i < 1; i++)
        {
            Stream stream = mParameters.session.getTrack(i);
            if (stream != null)
            {
                String request = "SETUP rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path + "/trackID=" + i + " RTSP/1.0\r\n" +
                                 "Transport: RTP/AVP/UDP;unicast;client_port=" + (5000 + 2 * i) + "-" + (5000 + 2 * i + 1) + ";mode=receive\r\n\r\n" +
                                 addHeaders();

                Log.i(TAG, request.substring(0, request.indexOf("\r\n")));

                mOutputStream.write(request.getBytes("UTF-8"));
                Response response = Response.parseResponse(mBufferedReader);
                Matcher m;
                try
                {
                    m = Response.rexegTransport.matcher(response.headers.get("transport"));
                    m.find();
                    stream.setDestinationPorts(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                    Log.d(TAG, "Setting destination ports: " + Integer.parseInt(m.group(3)) + ", " + Integer.parseInt(m.group(4)));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    int[] ports = stream.getDestinationPorts();
                    Log.d(TAG, "Server did not specify ports, using default ports: " + ports[0] + "-" + ports[1]);
                }
            }
        }
    }

    /**
     * ��װ������RECORD����¼������
     * ����SETUP�����������ݴ��䡣
     * �����Ự
     */
    private void sendRequestRecord() throws IllegalStateException, SocketException, IOException
    {
        String request = "RECORD rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path + " RTSP/1.0\r\n\r\n" +
                         "Range: npt=0.000-" +
                         addHeaders();
        Log.i(TAG, request.substring(0, request.indexOf("\r\n")));
        mOutputStream.write(request.getBytes("UTF-8"));
        Response response = Response.parseResponse(mBufferedReader);
        Log.v(TAG, "RTSP server RECORD:" + response.status);
    }

    /**
     * ��װ������TEARDOWN����٣�����
     * �ͷ�������Դ��RTSP����ֹͣ��
     */
    private void sendRequestTeardown() throws IOException
    {
        String request = "TEARDOWN rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path + " RTSP/1.0\r\n" + addHeaders();
        Log.i(TAG, request.substring(0, request.indexOf("\r\n")));
        mOutputStream.write(request.getBytes("UTF-8"));
    }

    /**
     * ��װ������OPTIONS��ѡ������
     * ѯ�ʷ�������֧�ֵķ�������
     */
    private void sendRequestOption() throws IOException
    {
        String request = "OPTIONS rtsp://" + mParameters.host + ":" + mParameters.port + mParameters.path + " RTSP/1.0\r\n" + addHeaders();
        Log.i(TAG, request.substring(0, request.indexOf("\r\n")));
        mOutputStream.write(request.getBytes("UTF-8"));
        Response.parseResponse(mBufferedReader);
    }

    private String addHeaders()
    {
        return "CSeq: " + (++mCSeq) + "\r\n" +
               "Content-Length: 0\r\n" +
               "Session: " + mSessionID + "\r\n" +
               (mAuthorization != null ? "Authorization: " + mAuthorization + "\r\n" : "");
    }

    /**
     * �����RTSP�����������Ӷ�ʧ,ֻҪ������{ @link # stopStream()}���Ǿͻ᳢���������ӵ�����
     */
    private Runnable mConnectionMonitor = new Runnable()
    {
        @Override
        public void run()
        {
            if (mState == STATE_STARTED)
            {
                try
                {
                    //���ǵ���RTSP������OPTION����
                    sendRequestOption();
                    mHandler.postDelayed(mConnectionMonitor, 6000);
                }
                catch (IOException e)
                {
                    //���OPTION����ʧ��
                    postMessage(ERROR_CONNECTION_LOST);
                    Log.e(TAG, "Connection lost with the server...");
                    mParameters.session.stop();
                    mHandler.post(mRetryConnection);
                }
            }
        }
    };

    /** ������,������ͼ�������ӵ��������� */
    private Runnable mRetryConnection = new Runnable()
    {
        @Override
        public void run()
        {
            if (mState == STATE_STARTED)
            {
                try
                {
                    Log.e(TAG, "Trying to reconnect...");
                    tryConnection();
                    try
                    {
                        mParameters.session.start();
                        mHandler.post(mConnectionMonitor);
                        postMessage(MESSAGE_CONNECTION_RECOVERED);
                    }
                    catch (Exception e)
                    {
                        abord();
                    }
                }
                catch (IOException e)
                {
                    mHandler.postDelayed(mRetryConnection, 1000);
                }
            }
        }
    };

    protected Session handleRequest(String uri, Socket client, String DestinationHost) throws IllegalStateException, IOException
    {
        Session session = UriParser.parse(uri);
        session.setOrigin(client.getLocalAddress().getHostAddress());
        if (session.getDestination() == null)
        {
            session.setDestination(DestinationHost);
        }
        return session;
    }

    final protected static char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private static String bytesToHex(byte[] bytes)
    {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ )
        {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /** Digest Access Authentication����� */
    private String computeMd5Hash(String buffer)
    {
        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("MD5");
            return bytesToHex(md.digest(buffer.getBytes("UTF-8")));
        }
        catch (NoSuchAlgorithmException ignore)
        {
        }
        catch (UnsupportedEncodingException e) {}
        return "";
    }

    private void postMessage(final int message)
    {
        mMainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                if (mCallback != null)
                {
                    mCallback.onRtspUpdate(message, null);
                }
            }
        });
    }

    private void postError(final int message, final Exception e)
    {
        mMainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                if (mCallback != null)
                {
                    mCallback.onRtspUpdate(message, e);
                }
            }
        });
    }

    static class Response
    {

        // ����������uri
        public static final Pattern regexStatus = Pattern.compile("RTSP/\\d.\\d (\\d+) (\\w+)", Pattern.CASE_INSENSITIVE);
        // ����һ������ͷ
        public static final Pattern rexegHeader = Pattern.compile("(\\S+):(.+)", Pattern.CASE_INSENSITIVE);
        // ����һ��WWW-Authenticateͷ
        public static final Pattern rexegAuthenticate = Pattern.compile("realm=\"(.+)\",\\s+nonce=\"(\\w+)\"", Pattern.CASE_INSENSITIVE);
        // ����һ���Ựͷ
        public static final Pattern rexegSession = Pattern.compile("(\\d+)", Pattern.CASE_INSENSITIVE);
        // ��������ͷ
        public static final Pattern rexegTransport = Pattern.compile("client_port=(\\d+)-(\\d+).+server_port=(\\d+)-(\\d+)", Pattern.CASE_INSENSITIVE);


        public int status;
        public HashMap<String, String> headers = new HashMap<String, String>();

        /** ��������,uri��RTSP�����ͷ */
        public static Response parseResponse(BufferedReader input) throws IOException, IllegalStateException, SocketException
        {
            Response response = new Response();
            String line;
            Matcher matcher;
            // �������󷽷���uri
            if ((line = input.readLine()) == null) throw new SocketException("Connection lost");
            Log.e(TAG, line);
            matcher = regexStatus.matcher(line);
            matcher.find();
            response.status = Integer.parseInt(matcher.group(1));

            // ���������ͷ
            while ( (line = input.readLine()) != null)
            {
                Log.e(TAG, "l: " + line.length() + "c: " + line);
                if (line.length() > 3)
                {
                    matcher = rexegHeader.matcher(line);
                    matcher.find();
                    response.headers.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
                }
                else
                {
                    break;
                }
            }
            if (line == null) throw new SocketException("Connection lost");

            Log.d(TAG, "Response from server: " + response.status);

            return response;
        }
    }
    /** ����������������ӣ��㽫���Binder*/
    public class LocalBinder extends Binder
    {
        public RtspClient getService()
        {
            return RtspClient.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent)
    {
        // TODO �Զ����ɵķ������
        return mBinder;
    }

}
