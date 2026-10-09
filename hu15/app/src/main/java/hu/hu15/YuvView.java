// SPDX-License-Identifier: AGPL-3.0-or-later
package hu.hu15;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** GLES 2.0 (API 8+) I420 renderer: three GL_LUMINANCE textures, BT.601 limited-range conversion in the fragment shader. */
public final class YuvView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String VS = "attribute vec4 p;attribute vec2 t;varying vec2 v;void main(){gl_Position=p;v=t;}";
    private static final String FS = "precision mediump float;varying vec2 v;uniform sampler2D y,u,w;void main(){"
            + "float Y=1.164*(texture2D(y,v).r-0.0625);float U=texture2D(u,v).r-0.5;float V=texture2D(w,v).r-0.5;"
            + "gl_FragColor=vec4(Y+1.596*V,Y-0.391*U-0.813*V,Y+2.018*U,1.0);}";
    private static final float[] QUAD = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0}; // x,y,s,t

    private final Object lock = new Object();
    private ByteBuffer pending;           // latest I420 frame, owned by the decoder; read under lock
    private int pw, ph;
    private boolean dirty;
    private int prog, texY, texU, texV, texW, texH;
    private final int[] tex = new int[3];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    public volatile long drawn;

    public YuvView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        quad.put(QUAD).position(0);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Guards the pending-frame handover (not the decode itself: the decoder alternates two buffers). */
    public Object lock() { return lock; }

    public void frameReady(ByteBuffer frame, int w, int h) {
        synchronized (lock) { pending = frame; pw = w; ph = h; dirty = true; }
        requestRender();
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig c) {
        prog = link(VS, FS);
        GLES20.glGenTextures(3, tex, 0);
        for (int i = 0; i < 3; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        texW = texH = 0;
    }

    /** Largest rect with the video's 800x480 aspect centred in a w x h surface: {left, top, width, height}. Shared with touch mapping. */
    public static int[] videoRect(int w, int h) {
        int vw = w, vh = w * Session.HEIGHT / Session.WIDTH;
        if (vh > h) { vh = h; vw = h * Session.WIDTH / Session.HEIGHT; }
        return new int[]{(w - vw) / 2, (h - vh) / 2, vw, vh};
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        int[] r = videoRect(w, h); // letterbox: never stretch the phone's picture
        GLES20.glViewport(r[0], r[1], r[2], r[3]);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        synchronized (lock) {
            if (pending == null) return;
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            boolean realloc = pw != texW || ph != texH;
            if (dirty || realloc) {
                int[] sizes = {pw, ph, pw / 2, ph / 2, pw / 2, ph / 2};
                int off = 0;
                for (int i = 0; i < 3; i++) {
                    int w = sizes[i * 2], h = sizes[i * 2 + 1];
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
                    pending.position(off);
                    if (realloc) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pending);
                    else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pending);
                    off += w * h;
                }
                pending.position(0);
                texW = pw; texH = ph; dirty = false;
            }
        }
        GLES20.glUseProgram(prog);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "y"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "u"), 1);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "w"), 2);
        int p = GLES20.glGetAttribLocation(prog, "p"), t = GLES20.glGetAttribLocation(prog, "t");
        quad.position(0); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(p);
        quad.position(2); GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(t);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        drawn++;
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs), p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log15.i("gl", "link failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log15.i("gl", "shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
