package com.android.wm.shell.transition;

import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import android.os.IBinder;
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

/** Plays open/close of the opening and closing app together with one shared spring. */
public class ParallelAppTransitionHandler implements Transitions.TransitionHandler {
    private static final float STIFFNESS = 600f;
    private static final float DAMPING = 0.88f;
    private static final float MIN_SCALE = 0.90f;

    @Override
    public boolean startAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            Transitions.TransitionFinishCallback finishCb) {
        int type = info.getType();
        if (type != TRANSIT_OPEN && type != TRANSIT_CLOSE
                && type != TRANSIT_TO_FRONT && type != TRANSIT_TO_BACK) {
            return false;
        }
        final List<TransitionInfo.Change> opening = new ArrayList<>();
        final List<TransitionInfo.Change> closing = new ArrayList<>();
        for (TransitionInfo.Change c : info.getChanges()) {
            if ((c.getFlags() & FLAG_IS_WALLPAPER) != 0) continue;
            if (c.getTaskInfo() == null && c.getActivityComponent() == null) continue;
            if (TransitionUtil.isOpeningType(c.getMode())) opening.add(c);
            else if (TransitionUtil.isClosingType(c.getMode())) closing.add(c);
            else return false; // anything unusual: let the default handler do it
        }
        if (opening.isEmpty() && closing.isEmpty()) return false;

        for (TransitionInfo.Change c : opening) {
            startT.setAlpha(c.getLeash(), 0f).show(c.getLeash());
        }
        startT.apply();

        final SpringAnimation spring = new SpringAnimation(new FloatValueHolder(0f));
        spring.setSpring(new SpringForce(1f)
                .setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        spring.setMinimumVisibleChange(0.001f);
        spring.addUpdateListener((anim, p, v) -> {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (TransitionInfo.Change c : opening) {
                apply(t, c, MIN_SCALE + (1f - MIN_SCALE) * p, Math.min(1f, p * 1.5f));
            }
            for (TransitionInfo.Change c : closing) {
                apply(t, c, 1f - (1f - MIN_SCALE) * p, Math.max(0f, 1f - p * 1.5f));
            }
            t.apply();
            t.close();
        });
        spring.addEndListener((anim, canceled, value, velocity) -> {
            for (TransitionInfo.Change c : opening) {
                finishT.setMatrix(c.getLeash(), 1, 0, 0, 1).setAlpha(c.getLeash(), 1f);
            }
            finishCb.onTransitionFinished(null);
        });
        spring.start();
        return true;
    }

    private static void apply(SurfaceControl.Transaction t, TransitionInfo.Change c,
            float scale, float alpha) {
        final SurfaceControl leash = c.getLeash();
        final float w = c.getEndAbsBounds().width();
        final float h = c.getEndAbsBounds().height();
        t.setAlpha(leash, alpha);
        t.setMatrix(leash, scale, 0, 0, scale);
        t.setPosition(leash, c.getEndRelOffset().x + w * (1f - scale) / 2f,
                c.getEndRelOffset().y + h * (1f - scale) / 2f);
    }

    @Override
    public WindowContainerTransaction handleRequest(IBinder transition,
            TransitionRequestInfo request) {
        return null;
    }
}
