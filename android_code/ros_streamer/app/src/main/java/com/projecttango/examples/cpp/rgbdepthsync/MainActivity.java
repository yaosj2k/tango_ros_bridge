/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// TODO: handle disconnects from the socket server (reset everything)
// TODO: PointCloud2 instead of PointCloud (faster)
// TODO: Add a text box for the IP Address
package com.projecttango.examples.cpp.rgbdepthsync;

import com.projecttango.examples.cpp.util.TangoInitializationHelper;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.wifi.WifiManager;
import android.net.wifi.ScanResult;
import android.graphics.Bitmap;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.os.IBinder;
import android.app.Activity;
import android.widget.ImageView;
import android.content.Intent;
import android.os.Bundle;
import android.os.AsyncTask;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.view.View;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.ServiceConnection;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.ArrayDeque;

/**
 * Main activity controls Tango lifecycle.
 */
public class MainActivity extends Activity {
  public static final String EXTRA_KEY_PERMISSIONTYPE = "PERMISSIONTYPE";

  private boolean threadsStarted = false;
  private SharedPreferences preferences;
  private SharedPreferences.Editor preferencesEditor;

  private boolean scanWifi = true;
  private boolean streamColorImages = true;
  private boolean connected = false;

  private String hostName = "";

  private double mostRecentPoseSent = 0.0;

  final int portNumberColorImages = 11111;
  final int portNumberPointCloud = 11112;
  final int portNumberPose = 11113;
  final int portNumberIntrinsicsColor = 11114;
  final int portNumberFisheyeImages = 11115;
  final int portNumberIntrinsicsFisheye = 11116;
  final int portNumberPoseArea = 11117;
  final int portNumberWIFIScan = 11118;

  private DatagramPacket scanPacket = null;
  private DatagramSocket udpFisheyeImages;
  private DatagramSocket udpColorImages;
  private DatagramSocket udpPointCloud;
  private DatagramSocket udpPose;
  private DatagramSocket udpAreaPose;
  private DatagramSocket udpIntrinsicsColor;
  private DatagramSocket udpIntrinsicsFisheye;
  private DatagramSocket udpWIFIScan = null;

  private Thread imagesColorThread;
  private Thread imagesFisheyeThread;
  private Thread poseThread;
  private Thread poseAreaThread;
  private Thread pointCloudThread;
  private Thread intrinsicsColorThread;
  private Thread intrinsicsFisheyeThread;
  private Thread scanThread;

  protected Bitmap bmColor;
  protected Bitmap bmFisheye;

  private InetAddress remote;

  private Queue<Double> pendingPoseTimeSteps = new ArrayDeque<Double>();

  private void addTimestamp(double timestamp) {
    synchronized (pendingPoseTimeSteps) {
        // prevent poses from coming in out of order (TODO: revisit this for slower sensors)
        if (timestamp > mostRecentPoseSent) {
            pendingPoseTimeSteps.add(new Double(timestamp));
        }
    }
  }

  private double[] getTimestamps() {
    double[] retval;
    synchronized (pendingPoseTimeSteps) {
      retval = new double[pendingPoseTimeSteps.size()];
      for (int i = 0; i < retval.length; i++) {
        retval[i] = pendingPoseTimeSteps.remove().doubleValue();
      }
    }
    return retval;
  }

  /**
   * Broadcast receiver to update
   */
  private BroadcastReceiver myRssiChangeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context arg0, Intent arg1) {
      WifiManager wifiMan = (WifiManager) getSystemService(Context.WIFI_SERVICE);
      System.out.println("RSSI: in scan broadcast receiver " + scanPacket + " " + remote);
      if (udpWIFIScan != null && remote != null && scanPacket == null) {
        ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
        List<ScanResult> detected_aps = wifiMan.getScanResults();
        // it appears that the Tango clock is the same as SystemClock.elapsedRealTimeNanos()/1e9
        double[] currPose = TangoJNINative.returnPoseArray();
        // we have no way to query the current Tango time, so instead we use the time of the last available pose
        double scanTimestamp = currPose[7];
        System.out.println("RSSI: about to create message " + scanTimestamp + " " + currPose[7]);
        double timeThreshold = 0.2;         // don't send any measurements that are 0.2 seconds older than the most recent reading
        double mostRecentDetection = 0.0;
        for (int i = 0; i < detected_aps.size(); i++) {
          if (detected_aps.get(i).timestamp / ((float)1e6) > mostRecentDetection) {
            mostRecentDetection = detected_aps.get(i).timestamp/((float)1e6);
          }
        }
        try {
          udpPayload.write("RSSISTART\n".getBytes());
          udpPayload.write(("RSSISCANTIMESTART" + scanTimestamp + "RSSISCANTIMEEND").getBytes());
          for (int i = 0; i < detected_aps.size(); i++) {
            float apTimeStamp = detected_aps.get(i).timestamp / (float)1e6;
            if (mostRecentDetection - apTimeStamp < timeThreshold) {
              udpPayload.write(("BSSID " + detected_aps.get(i).BSSID + " " + detected_aps.get(i).level + "\n").getBytes());
            }
          }
          udpPayload.write("RSSIEND\n".getBytes());
          byte[] payload = udpPayload.toByteArray();
          scanPacket = new DatagramPacket(payload, payload.length, remote, portNumberWIFIScan);
        } catch (IOException e) {
          e.printStackTrace();
        }
        System.out.println("RSSI: received scan");
      }
      if (scanWifi) {
        System.out.println("RSSI: restarting scan");
        wifiMan.startScan();
      }
    }
  };

  // Project Tango Service connection.
  ServiceConnection mTangoServiceConnection = new ServiceConnection() {
    public void onServiceConnected(ComponentName name, IBinder service) {
      TangoJNINative.setBinder(service);
      System.out.println("PAUL: " + streamColorImages);

      TangoJNINative.setupConfig(streamColorImages);
      TangoJNINative.connectCallbacks(streamColorImages);
      TangoJNINative.connect();

      if (threadsStarted) {
        return;
      }

      try {
        udpFisheyeImages = new DatagramSocket();
        udpColorImages = new DatagramSocket();
        udpPointCloud = new DatagramSocket();
        udpPose = new DatagramSocket();
        udpAreaPose = new DatagramSocket();
        udpIntrinsicsColor = new DatagramSocket();
        udpIntrinsicsFisheye = new DatagramSocket();
        udpWIFIScan = new DatagramSocket();
      } catch (SocketException e) {
        e.printStackTrace();
      }
      intrinsicsFisheyeThread = new Thread(new Runnable() {
        public void run() {
          while (true) {
            double[] currIntrinsics = TangoJNINative.returnIntrinsicsFisheye();
            if (connected) {
              ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
              String intrinsicsAsString = Arrays.toString(currIntrinsics);

              try {
                udpPayload.write("INTRINSICSSTARTINGRIGHTNOW\n".getBytes());
                udpPayload.write((intrinsicsAsString.substring(1, intrinsicsAsString.length() - 1) + "\n").getBytes());
                udpPayload.write("INTRINSICSENDINGRIGHTNOW\n".getBytes());
                byte[] payload = udpPayload.toByteArray();
                udpIntrinsicsFisheye.send(new DatagramPacket(payload, payload.length, remote, portNumberIntrinsicsFisheye));
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
            try {
              // throttle the speed at which we send data.  The camera intrinsics don't change, so no need to go nuts here.
              Thread.sleep(1000);
            } catch (InterruptedException ex) {
              System.err.println("Something weird happened");
            }
          }
        }
      });

      intrinsicsColorThread = new Thread(new Runnable() {
        public void run() {
          while (true) {
            double[] currIntrinsics = TangoJNINative.returnIntrinsicsColor();
            if (connected) {
              ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
              String intrinsicsAsString = Arrays.toString(currIntrinsics);

              try {
                udpPayload.write("INTRINSICSSTARTINGRIGHTNOW\n".getBytes());
                udpPayload.write((intrinsicsAsString.substring(1, intrinsicsAsString.length() - 1) + "\n").getBytes());
                udpPayload.write("INTRINSICSENDINGRIGHTNOW\n".getBytes());
                byte[] payload = udpPayload.toByteArray();
                udpIntrinsicsColor.send(new DatagramPacket(payload, payload.length, remote, portNumberIntrinsicsColor));
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
            try {
              // throttle the speed at which we send data.  The camera intrinsics don't change, so no need to go nuts here.
              Thread.sleep(1000);
            } catch (InterruptedException ex) {
              System.err.println("Something weird happened");
            }
          }
        }
      });


      pointCloudThread = new Thread(new Runnable() {
        public void run() {
          double lastPointCloudTimeStampSent = 0.0;

          while (true) {
            int max_floats_per_packet = 15000;
            float[] currPointCloud = TangoJNINative.returnPointCloud();

            if (currPointCloud[0] != lastPointCloudTimeStampSent) {
              addTimestamp(currPointCloud[0]);
              lastPointCloudTimeStampSent = currPointCloud[0];
              System.out.println("POINT CLOUD SIZE " + currPointCloud.length);
              if (connected) {
                ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
                try {
                  udpPayload.write("POINTCLOUDSTARTINGRIGHTNOW\n".getBytes());
                  byte[] buf;
                  int remainingFloats = currPointCloud.length;

                  if (remainingFloats > max_floats_per_packet) {
                    buf = new byte[4 * max_floats_per_packet];
                  } else {
                    buf = new byte[4 * remainingFloats];
                  }
                  int buffPosition = 0;

                  for (int i = 0; i < currPointCloud.length; ++i) {
                    if (buffPosition >= max_floats_per_packet) {
                      udpPayload.write(buf);
                      byte[] payload = udpPayload.toByteArray();
                      udpPointCloud.send(new DatagramPacket(payload, payload.length, remote, portNumberPointCloud));
                      udpPayload = new ByteArrayOutputStream();
                      buffPosition = 0;
                      if (remainingFloats > max_floats_per_packet) {
                        buf = new byte[4 * max_floats_per_packet];
                      } else {
                        System.out.println("POINT allocating partial packet " + remainingFloats);
                        buf = new byte[4 * remainingFloats];
                      }
                    }
                    int val = Float.floatToRawIntBits(currPointCloud[i]);
                    buf[4 * buffPosition] = (byte) (val >> 24);
                    buf[4 * buffPosition + 1] = (byte) (val >> 16);
                    buf[4 * buffPosition + 2] = (byte) (val >> 8);
                    buf[4 * buffPosition + 3] = (byte) (val);
                    buffPosition++;
                    remainingFloats--;
                  }
                  udpPayload.write(buf);
                  udpPayload.write("POINTCLOUDENDINGRIGHTNOW\n".getBytes());
                  byte[] payload = udpPayload.toByteArray();
                  udpPointCloud.send(new DatagramPacket(payload, payload.length, remote, portNumberPointCloud));
                } catch (Exception ex) {
                  System.out.println("ERROR:  " + ex.getMessage());
                }
              }
            }
            try {
              // throttle the speed at which we send data
              // TODO: allow this to be configured via some sort of user interface widget
              // TODO: use some sort of synchronization to avoid spin lock
              Thread.sleep(100);
            } catch (InterruptedException ex) {
              System.err.println("Thread was interrupted!");
            }
          }
        }
      });

      imagesColorThread = new Thread(new Runnable() {
        public void run() {
          double lastTimeStamp = 0.0;
          while (true) {
            if (TangoJNINative.getColorFrameTimestamp() != lastTimeStamp) {
              double[] frameTimeStamp = new double[1];
              int[] dimensions = new int[2];
              byte[] myArray = TangoJNINative.returnArrayColor(frameTimeStamp, dimensions);
              YuvImage colorFrame = new YuvImage(myArray, android.graphics.ImageFormat.NV21, dimensions[0], dimensions[1], null);
              lastTimeStamp = frameTimeStamp[0];
              addTimestamp(frameTimeStamp[0]);

              if (myArray != null && myArray.length != 0) {
                if (connected) {
                  try {
                    ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
                    udpPayload.write("DEPTHFRAMESTARTINGRIGHTNOW\n".getBytes());
                    udpPayload.write("DEPTHTIMESTAMPSTARTINGRIGHTNOW\n".getBytes());
                    String frameTimeStampAsString = String.valueOf(frameTimeStamp[0]);
                    udpPayload.write((frameTimeStampAsString + "\n").getBytes());
                    udpPayload.write("DEPTHTIMESTAMPENDINGRIGHTNOW\n".getBytes());
                    colorFrame.compressToJpeg(new Rect(0, 0, colorFrame.getWidth(), colorFrame.getHeight()), 10, udpPayload);
                    udpPayload.write("DEPTHFRAMEENDINGRIGHTNOW\n".getBytes());
                    byte[] payload = udpPayload.toByteArray();
                    udpColorImages.send(new DatagramPacket(payload, payload.length, remote, portNumberColorImages));
                  } catch (IOException ex) {
                    ex.printStackTrace();
                    System.err.println("ERROR!");
                  }
                }
              }
            }
            try {
              // throttle the speed at which we send data
              // TODO: allow this to be configured via some sort of user interface widget
              Thread.sleep(100);
            } catch (InterruptedException ex) {
              System.err.println("Something weird happened");
            }
          }
        }
      });

      imagesFisheyeThread = new Thread(new Runnable() {
        public void run() {
          double lastTimeStamp = 0.0;
          while (true) {
            if (TangoJNINative.getFisheyeFrameTimestamp() != lastTimeStamp) {
              long start = System.currentTimeMillis();
              double[] frameTimeStamp = new double[1];
              int[] fisheyeStride = new int[1];

              byte[] myArray = TangoJNINative.returnArrayFisheye(frameTimeStamp, fisheyeStride);
              // the fisheye image uses a stride that is not the same as the image width
              int[] strides = new int[2];
              strides[0] = fisheyeStride[0];
              strides[1] = fisheyeStride[0];
              YuvImage fisheyeFrame = new YuvImage(myArray, android.graphics.ImageFormat.NV21, 640, 480, strides);

              lastTimeStamp = frameTimeStamp[0];
              addTimestamp(frameTimeStamp[0]);
              if (myArray != null && myArray.length != 0) {
                if (connected) {
                  try {
                    ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
                    udpPayload.write("DEPTHFRAMESTARTINGRIGHTNOW\n".getBytes());
                    udpPayload.write("DEPTHTIMESTAMPSTARTINGRIGHTNOW\n".getBytes());
                    String frameTimeStampAsString = String.valueOf(frameTimeStamp[0]);
                    udpPayload.write((frameTimeStampAsString + "\n").getBytes());
                    udpPayload.write("DEPTHTIMESTAMPENDINGRIGHTNOW\n".getBytes());
                    fisheyeFrame.compressToJpeg(new Rect(0, 0, fisheyeFrame.getWidth(), fisheyeFrame.getHeight()), 80, udpPayload);
                    udpPayload.write("DEPTHFRAMEENDINGRIGHTNOW\n".getBytes());
                    byte[] payload = udpPayload.toByteArray();
                    udpFisheyeImages.send(new DatagramPacket(payload, payload.length, remote, portNumberFisheyeImages));
                  } catch (IOException ex) {
                    ex.printStackTrace();
                    System.err.println("ERROR!");
                  }
                }
              }
            }
            try {
              // throttle the speed at which we send data
              // TODO: allow this to be configured via some sort of user interface widget
              Thread.sleep(100);
            } catch (InterruptedException ex) {
              System.err.println("Something weird happened");
            }
          }
        }
      });


      poseThread = new Thread(new Runnable() {
        public void run() {
            int TANGO_POSE_VALID = 1;
            double dt = .025;
            while (true) {
              double[] posesToSend = getTimestamps();
              if (posesToSend.length != 0) {
                  double firstPose = mostRecentPoseSent + dt;
                  double lastPose;
                  Arrays.sort(posesToSend);
                  double lastDesiredPose = posesToSend[posesToSend.length - 1];

                  if (mostRecentPoseSent + 1.0 < lastDesiredPose) {
                      firstPose = lastDesiredPose - 1.0;
                  }
                  int numPosesToSend = (int)Math.ceil((lastDesiredPose - firstPose)/dt) + 1;
                  posesToSend = new double[numPosesToSend];
                  for (int i = 0; i < posesToSend.length; i++) {
                      posesToSend[i] = firstPose + i*dt;
                  }
                  double[] poses = TangoJNINative.getPoses(posesToSend);
                  int doublesPerPose = poses.length / posesToSend.length;
                  for (int i = 0; i < posesToSend.length; i++) {
                      if (poses[i*doublesPerPose+8] ==  TANGO_POSE_VALID && posesToSend[i] > mostRecentPoseSent) {
                          mostRecentPoseSent = posesToSend[i];
                      }
                  }
                  for (int i = 0; i < posesToSend.length; i++) {
                      if (poses[i * doublesPerPose + 8] != TANGO_POSE_VALID) {
                          addTimestamp(posesToSend[i]);
                      }
                  }
                  if (connected) {
                      ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
                      try {
                          udpPayload.write("POSESTARTINGRIGHTNOW\n".getBytes());
                          for (int i = 0; i < poses.length; i++) {
                              udpPayload.write(String.valueOf(poses[i]).getBytes());
                              if (i + 1 < poses.length) {
                                  udpPayload.write(",".getBytes());
                              }
                          }
                          udpPayload.write("POSEENDINGRIGHTNOW\n".getBytes());
                          byte[] payload = udpPayload.toByteArray();
                          udpPose.send(new DatagramPacket(payload, payload.length, remote, portNumberPose));
                      } catch (IOException e) {
                          e.printStackTrace();
                      }
                  }
              }
              try {
                  // throttle the speed at which we send data
                  // TODO: allow this to be configured via some sort of user interface widget
                  Thread.sleep(200);
              } catch (InterruptedException ex) {
                  System.err.println("Something weird happened");
              }
          }
        }
      });

//      poseThread = new Thread(new Runnable() {
//        public void run() {
//          double lastPoseSentTimeStamp = 0.0;
//          while (true) {
//            double[] currPose = TangoJNINative.returnPoseArray();
//            if (currPose[7] != lastPoseSentTimeStamp) {
//              // remember the pose timestamp so we don't send the same one multiple times
//              lastPoseSentTimeStamp = currPose[7];
//              if (connected) {
//                ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
//                try {
//                  udpPayload.write("POSESTARTINGRIGHTNOW\n".getBytes());
//                  for (int i = 0; i < currPose.length; i++) {
//                    udpPayload.write(String.valueOf(currPose[i]).getBytes());
//
//                    if (i + 1 < currPose.length) {
//                      udpPayload.write(",".getBytes());
//                    }
//                  }
//                  udpPayload.write("POSEENDINGRIGHTNOW\n".getBytes());
//                  byte[] payload = udpPayload.toByteArray();
//                  udpPose.send(new DatagramPacket(payload, payload.length, remote, portNumberPose));
//                } catch (IOException e) {
//                  e.printStackTrace();
//                }
//              }
//            }
//            try {
//              // throttle the speed at which we send data
//              // TODO: allow this to be configured via some sort of user interface widget
//              Thread.sleep(5);
//            } catch (InterruptedException ex) {
//              System.err.println("Something weird happened");
//            }
//          }
//        }
//      });

//      poseAreaThread = new Thread(new Runnable() {
//        public void run() {
//          while (true) {
//            double[] currPose = TangoJNINative.returnPoseAreaArray();
//            if (connected) {
//              ByteArrayOutputStream udpPayload = new ByteArrayOutputStream();
//              try {
//                udpPayload.write("POSESTARTINGRIGHTNOW\n".getBytes());
//                for (int i = 0; i < currPose.length; i++) {
//                  udpPayload.write(String.valueOf(currPose[i]).getBytes());
//
//                  if (i + 1 < currPose.length) {
//                    udpPayload.write(",".getBytes());
//                  }
//                }
//                udpPayload.write("POSEENDINGRIGHTNOW\n".getBytes());
//                byte[] payload = udpPayload.toByteArray();
//                udpAreaPose.send(new DatagramPacket(payload, payload.length, remote, portNumberPoseArea));
//              } catch (IOException e) {
//                e.printStackTrace();
//              }
//            }
//            try {
//              // throttle the speed at which we send data
//              // TODO: allow this to be configured via some sort of user interface widget
//              Thread.sleep(100);
//            } catch (InterruptedException ex) {
//              System.err.println("Something weird happened");
//            }
//          }
//        }
//      });

      scanThread = new Thread(new Runnable() {
        public void run() {
          while (true) {
            if (connected) {
              if (scanPacket != null) {
                System.out.println("RSSI sending new packet\n");
                try {
                  udpWIFIScan.send(scanPacket);
                  scanPacket = null;
                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
            }
            try {
              // throttle the speed at which we send data
              // TODO: allow this to be configured via some sort of user interface widget
              Thread.sleep(100);
            } catch (InterruptedException ex) {
              System.err.println("Something weird happened");
            }
          }
        }
      });
      // start all of the threads
      // TODO: allow these to be turned on and off from the app
      if (scanWifi) {
        scanThread.start();
      }
      poseThread.start();
      //poseAreaThread.start();
      pointCloudThread.start();
      intrinsicsFisheyeThread.start();
      imagesFisheyeThread.start();
      if (streamColorImages) {
        intrinsicsColorThread.start();
        imagesColorThread.start();
      }
      threadsStarted = true;
    }

    public void onServiceDisconnected(ComponentName name) {
      System.out.println("PAUL: service disconnected");
      // Handle this if you need to gracefully shut down/retry in the event
      // that Project Tango itself crashes/gets upgraded while running.
    }
  };


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    System.out.println("PAUL: on create");
    setContentView(R.layout.activity_main);
    setTitle(R.string.app_name);
    // Tango fish eye
    bmFisheye = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
    // tango color camera
    bmColor = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);

    ImageView image = (ImageView) findViewById(R.id.test_image);
    image.setImageBitmap(bmColor);
    preferences = getSharedPreferences("myvals", 0);
    preferencesEditor = preferences.edit();
    EditText mEdit = (EditText) findViewById(R.id.editText1);
    mEdit.setText(preferences.getString("ROS_HOST", ""));
  }

  public void toggleWifiScanStatus(View v) {
    boolean isChecked = ((CheckBox) findViewById(R.id.checkBox)).isChecked();
    if (isChecked) {
      // need to kick this off again
      WifiManager wifiMan = (WifiManager) getSystemService(Context.WIFI_SERVICE);
      wifiMan.startScan();
    }
    scanWifi = isChecked;
    System.out.println("Changing RSSI status");
  }

  public void toggleConnectionStatus(View v) {
    if (!connected) {
      EditText mEdit = (EditText) findViewById(R.id.editText1);
      hostName = mEdit.getText().toString();
      try {
        remote = InetAddress.getByName(hostName);
      } catch (Exception ex) {
        ex.printStackTrace();
      }
      System.out.println("button clicked!! " + mEdit.getText().toString());
      preferencesEditor.putString("ROS_HOST", hostName);
      preferencesEditor.commit();
      new AsyncTask<Void, Integer, Void>() {
        @Override
        protected Void doInBackground(Void... arg0) {
          try {
            // since we are using UDP there isn't much we have to do, just start
            // blasting out the data
            connected = true;
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return null;
        }

        @Override
        protected void onPostExecute(Void result) {
          if (connected) {
            ((Button) findViewById(R.id.button1)).setText("Disconnect");
          } else {
            ((Button) findViewById(R.id.button1)).setText("Connect");
          }
        }

        @Override
        protected void onPreExecute() {
        }
      }.execute((Void) null);
    } else {
      new AsyncTask<Void, Integer, Void>() {
        @Override
        protected Void doInBackground(Void... arg0) {
          try {
            // nothing much to do, everything is UDP, just stop sending data
            connected = false;
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          return null;
        }

        @Override
        protected void onPostExecute(Void result) {
          System.out.println("PAUL: setting text of button");
          if (connected) {
            ((Button) findViewById(R.id.button1)).setText("Disconnect");
          } else {
            ((Button) findViewById(R.id.button1)).setText("Connect");
          }
          System.out.println("PAUL: setting text of button done");
        }

        @Override
        protected void onPreExecute() {
        }
      }.execute((Void) null);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    System.out.println("PAUL: onResume");
    TangoInitializationHelper.bindTangoService(this, mTangoServiceConnection);
    if (scanWifi) {
      IntentFilter rssiFilter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
      this.registerReceiver(myRssiChangeReceiver, rssiFilter);
      System.out.println("RSSI: requesting scan");
      WifiManager wifiMan= (WifiManager)getSystemService(Context.WIFI_SERVICE);
      wifiMan.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, "tango_wifi_lock" );
      wifiMan.startScan();
    }
  }

  @Override
  public void onSaveInstanceState(Bundle savedInstanceState) {
    // TODO: need to handle this properly for cases when the activity is suspended by Android (e.g., when plugging into the charger)
    // Always call the superclass so it can save the view hierarchy state
    super.onSaveInstanceState(savedInstanceState);
    System.out.println("PAUL: onSaveInstanceState");
  }

  @Override
  protected void onStop() {
    System.out.println("PAUL: onStop");
    super.onStop();
    // this is not being handled properly.  need to shut stuff down
  }

  @Override
  protected void onPause() {
    System.out.println("PAUL: onPause");

    super.onPause();
    if (scanWifi) {
      unregisterReceiver(myRssiChangeReceiver);
    }
    System.out.println("MYDEBUG: DISCONNECTING FROM TANGO!!!");
    TangoJNINative.disconnect();
    unbindService(mTangoServiceConnection);
  }
}