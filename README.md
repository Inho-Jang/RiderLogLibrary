# RiderLog Library
Rider Log Service 모듈 배포 작업(테스트)

### Jitpack을 이용해 라이더로그 라이브러리 모듈을 implementation한다
   1. build.gradle에 jitpack repository 추가
       ```
       allprojects {
           repositories {
               ...
               maven { url 'https://jitpack.io' }
           }
       }
       ```
   2. Dependency 추가 - 필요한 버전의 Tag를 입력
       ```
       dependencies {
           implementation 'com.github.Inho-Jang:RiderLogLibrary:TAG'
       }
       ```
       TAG에는 Release된 Library version을 적용
   3. Gradle sync 진행
### 핵심 Service인 RLGeneralService를 상속받는 서비스를 생성
   1. 해당 서비스를 실행하려면 'Background Location'(ACCESS_BACKGROUND_LOCATION)과 'System Alert Window'(or Settings.ACTION_MANAGE_OVERLAY_PERMISSION) 권한이 필수
   2. startForegroundService로 서비스 시작
   3. Foreground Service를 위한 Notification 생성
       ```java
       private final int RIDER_LOG_NOTIFICATION_ID = 1;
   
       String RIDER_LOG_CHANNEL_ID = notificationContext.getString(com.starpickers.riderloglibrary.R.string.notification_id);
       String RIDER_LOG_CHANNEL_NAME = notificationContext.getString(com.starpickers.riderloglibrary.R.string.notification_name);
    
       NotificationManager notificationManager = getSystemService(NotificationManager.class);
       NotificationChannel channel = new NotificationChannel(RIDER_LOG_CHANNEL_ID, RIDER_LOG_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
       notificationManager.createNotificationChannel(channel);
        
       Intent openAppIntent = new Intent(RLApp.getAppContext(), MainActivity.class);
       //openAppIntent.setAction(Intent.ACTION_MAIN);
       openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
       PendingIntent notificationPendingIntent = PendingIntent.getActivity(context, RIDER_LOG_NOTIFICATION_ID, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
       Notification.Builder builder = new Notification.Builder(getApplicationContext(), RIDER_LOG_CHANNEL_ID)
               .setSmallIcon(com.starpickers.dynamicmotionlibrary.R.drawable.ic_logo)
               .setContentTitle(notificationContext.getString(com.starpickers.dynamicmotionlibrary.R.string.running_service_title))
               .setContentText(notificationContext.getString(com.starpickers.dynamicmotionlibrary.R.string.running_service_message))
               .setContentIntent(notificationPendingIntent)
               .setAutoCancel(true);
       ```
   4. 3.의 Notification으로 onStartCommand()에서 startForeground()로 서비스 시작
       ```java
       startForeground(RIDER_LOG_NOTIFICATION_ID, mNotification);
       ```
### 서비스 동작에 필요한 기능 구현
   1. 블루투스 스캔 및 스캔 결과와 연결
   2. 사용자 전화번호(인증) 입력
   3. 파일 다운로드 권한 받기
   4. 센서 부착 후 정확한 주행 정보 저장을 위한 보정 버튼
