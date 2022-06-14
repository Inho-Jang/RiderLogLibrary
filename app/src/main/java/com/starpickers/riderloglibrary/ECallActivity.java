package com.starpickers.riderloglibrary;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ECallActivity extends AppCompatActivity {

    private TextView countdownTextView;
    private boolean accidentNotOccur = false;
    private Thread beep;

    private final long USER_ACTION_LIMIT = 90;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //getWindow().setBackgroundDrawable(new PaintDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            keyguardManager.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | PixelFormat.TRANSLUCENT);
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        layoutParams.dimAmount = 0.6f;
        getWindow().setAttributes(layoutParams);

        setContentView(R.layout.activity_ecall);
        this.setFinishOnTouchOutside(false);

        countdownTextView = findViewById(R.id.countdownTextView);
        Button btnAccidentNotOccur = findViewById(R.id.btn_not_accident);

        playAlertTone();

        //카운트다운 view
        CountDownTimer countDownTimer = new CountDownTimer(USER_ACTION_LIMIT * 1000, 1000) {
            public void onTick(long millisUntilFinished) {
                int num = (int) (millisUntilFinished / 1000) + 1;
                countdownTextView.setText(String.valueOf(num));
            }

            public void onFinish() {
                finishAffinity();
            }
        }.start();

        // 사고 미발생 버튼 리스너
        btnAccidentNotOccur.setOnClickListener(v -> {
            setAccidentNotOccur();
            Intent accidentNotOccur = new Intent("NOT_OCCUR");
            LocalBroadcastManager.getInstance(ECallActivity.this).sendBroadcast(accidentNotOccur);

            countDownTimer.cancel();
            finishAffinity();
        });
    }

    public void playAlertTone(){
        ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_RING, ToneGenerator.MAX_VOLUME);

        beep = new Thread(() -> {
            while(true){
                try{
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_HIGH_PBX_SS, 500);
                    Thread.sleep(1200);
                    if (isAccidentNotOccur()) {
                        break;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e){
                    e.printStackTrace();
                }
            }
        });

        beep.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return event.getAction() != MotionEvent.ACTION_OUTSIDE;
    }

    private void setAccidentNotOccur(){
        accidentNotOccur = true;
    }

    public boolean isAccidentNotOccur(){
        return accidentNotOccur;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        beep.interrupt();
    }
}