package com.android.wm.shell.transition;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.wm.shell.shared.TransitionUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * HyperOS-style app transitions. Every window owns one spring "progress" p
 * (1 = fully shown, 0 = gone). Opening windows animate p to 1, closing windows
 * to 0. A new transition just flips the target of the same spring, so velocity
 * is kept and animations overlap instead of queueing.
 */
public class ParallelAppTransitionHandler implements Transitions.TransitionHandler {
    // Tuning knobs
    private static final float STIFFNESS = 520f;
    private static final float DAMPING_RATIO = 0.86f;   // <1 gives a slight overshoot
    private static final float TOP_MIN_SCALE = 0.88f;   // incoming/outgoing top window
    private static final float BOTTOM_MIN_SCALE = 0.94f; // window underneath
    private static final float CORNER_RADIUS_DP = 28f;
    private static final float REST_THRESHOLD = 0.002f;

    private final float mCornerRadiusPx;
    private final SurfaceControl.Transaction mT = new SurfaceControl.Transaction();
    private final ArrayMap<IBinder, Playing> mPlaying = new ArrayMap<>();

    public ParallelAppTransitionHandler(Context context) {
        mCornerRadiusPx = CORNER_RADIUS_DP * context.getResources().getDisplayMetrics().density;
    }

    private static class Playing {
        IBinder token;
        Transitions.TransitionFinishCallback finishCb;
        SurfaceControl.Transaction finishT;
        final ArrayMap<Integer, WindowAnim> anims = new ArrayMap<>();
    }

    private final class WindowAnim {
        final Playing owner;
        final FloatValueHolder holder;
        final SpringAnimation spring;
        SurfaceControl leash;
        Rect bounds = new Rect();
        Point offset = new Point();
        boolean onTop;
        boolean settled = true;
        float target;

        WindowAnim(Playing owner, TransitionInfo.Change c, float startP) {
            this.owner = owner;
            holder = new FloatValueHolder(startP);
            spring = new SpringAnimation(holder);
            spring.setSpring(new SpringForce()
                    .setStiffness(STIFFNESS).setDampingRatio(DAMPING_RATIO));
            spring.setMinimumVisibleChange(REST_THRESHOLD);
            spring.addUpdateListener((a, value, velocity) -> {
                apply(mT, value);
                mT.apply();
            });
            spring.addEndListener((a, canceled, value, velocity) -> {
                settled = true;
                checkFinished(owner);
            });
            retarget(c);
        }

        void retarget(TransitionInfo.Change c) {
            leash = c.getLeash();
            bounds = new Rect(c.getEndAbsBounds());
            if (bounds.isEmpty()) bounds = new Rect(c.getStartAbsBounds());
            offset = new Point(c.getEndRelOffset());
        }

        void go() {
            settled = false;
            spring.animateToFinalPosition(target);
        }

        void apply(SurfaceControl.Transaction t, float p) {
            final float scale;
            final float alpha;
            final float radius;
            if (onTop) {
                scale = TOP_MIN_SCALE + (1f - TOP_MIN_SCALE) * p;
                alpha = Math.max(0f, Math.min(1f, p * 2.2f));
                radius = mCornerRadiusPx * (1f - Math.max(0f, Math.min(1f, p)));
            } else {
                scale = BOTTOM_MIN_SCALE + (1f - BOTTOM_MIN_SCALE) * p;
                alpha = 1f;
                radius = 0f;
            }
            t.setAlpha(leash, alpha);
            t.setMatrix(leash, scale, 0, 0, scale);
            t.setPosition(leash,
                    offset.x + bounds.width() * (1f - scale) / 2f,
                    offset.y + bounds.height() * (1f - scale) / 2f);
            t.setCornerRadius(leash, radius);
        }
    }

    private static boolean isWindow(TransitionInfo.Change c) {
        if ((c.getFlags() & FLAG_IS_WALLPAPER) != 0) return false;
        if (c.getParent() != null) return false; // nested activity change
        return c.getTaskInfo() != null || c.getActivityComponent() != null;
    }

    private static boolean isSupported(TransitionInfo info) {
        final int type = info.getType();
        if (type != TRANSIT_OPEN && type != TRANSIT_CLOSE
                && type != TRANSIT_TO_FRONT && type != TRANSIT_TO_BACK) {
            return false;
        }
        int windows = 0;
        for (TransitionInfo.Change c : info.getChanges()) {
            if (!isWindow(c)) continue;
            if (c.getTaskInfo() != null
                    && c.getTaskInfo().getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
                return false; // split, freeform, pip: leave to default handler
            }
            final int m = c.getMode();
            if (!TransitionUtil.isOpeningType(m) && !TransitionUtil.isClosingType(m)) {
                return false;
            }
            windows++;
        }
        return windows > 0;
    }

    /** Creates or retargets one spring per window of this transition. */
    private void addChanges(Playing playing, TransitionInfo info, SurfaceControl.Transaction t) {
        final boolean openType = TransitionUtil.isOpeningType(info.getType());
        final int n = info.getChanges().size();
        final List<WindowAnim> toStart = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final TransitionInfo.Change c = info.getChanges().get(i);
            final SurfaceControl leash = c.getLeash();
            if (!isWindow(c)) {
                if (TransitionUtil.isOpeningType(c.getMode())) t.show(leash).setAlpha(leash, 1f);
                continue;
            }
            final boolean opening = TransitionUtil.isOpeningType(c.getMode());
            final int key = c.getTaskInfo() != null
                    ? c.getTaskInfo().taskId : System.identityHashCode(leash);
            WindowAnim a = playing.anims.get(key);
            if (a == null) {
                a = new WindowAnim(playing, c, opening ? 0f : 1f);
                playing.anims.put(key, a);
            } else {
                a.retarget(c);
            }
            a.onTop = (opening == openType); // the window being navigated to/from is on top
            a.target = opening ? 1f : 0f;
            t.setLayer(leash, a.onTop ? n + 1 : i);
            t.show(leash);
            a.apply(t, a.holder.getValue());
            toStart.add(a);
        }
        t.apply();
        for (WindowAnim a : toStart) a.go();
    }

    @Override
    public boolean startAnimation(IBinder token, TransitionInfo info,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            Transitions.TransitionFinishCallback finishCb) {
        if (!isSupported(info)) return false;
        final Playing playing = new Playing();
        playing.token = token;
        playing.finishCb = finishCb;
        playing.finishT = finishT;
        mPlaying.put(token, playing);
        addChanges(playing, info, startT);
        return true;
    }

    @Override
    public void mergeAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction t, IBinder mergeTarget,
            Transitions.TransitionFinishCallback finishCb) {
        final Playing playing = mPlaying.get(mergeTarget);
        if (playing == null || !isSupported(info)) return; // not merged
        addChanges(playing, info, t);   // retarget springs, keep their velocity
        finishCb.onTransitionFinished(null);
    }

    private void checkFinished(Playing p) {
        for (WindowAnim a : p.anims.values()) {
            if (!a.settled) return;
        }
        mPlaying.remove(p.token);
        for (WindowAnim a : p.anims.values()) {
            final SurfaceControl l = a.leash;
            if (a.target == 0f) {
                mT.hide(l);
                p.finishT.hide(l);
            }
            mT.setMatrix(l, 1, 0, 0, 1).setAlpha(l, 1f).setCornerRadius(l, 0f)
                    .setPosition(l, a.offset.x, a.offset.y);
            p.finishT.setMatrix(l, 1, 0, 0, 1).setAlpha(l, 1f).setCornerRadius(l, 0f)
                    .setPosition(l, a.offset.x, a.offset.y);
        }
        mT.apply();
        p.finishCb.onTransitionFinished(null);
    }

    @Override
    public WindowContainerTransaction handleRequest(IBinder transition,
            TransitionRequestInfo request) {
        return null;
    }
}
