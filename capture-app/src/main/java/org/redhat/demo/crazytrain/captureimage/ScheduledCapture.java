package org.redhat.demo.crazytrain.captureimage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.jboss.logging.Logger;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.redhat.demo.crazytrain.mqtt.MqttPublisher;
import org.redhat.demo.crazytrain.util.Util;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.core.Vertx;


/**
 * ScheduledCapture is a service that captures images from a camera using the OpenCV library
 */

@ApplicationScoped
@Path("/capture")
public class ScheduledCapture {
    private  VideoCapture camera; 

    @Inject
    ImageCaptureService imageCaptureService;

    @Inject
    ImageService imageService;

    @Inject
    Vertx vertx;
    // interval in milliseconds
    @ConfigProperty(name = "capture.interval")
    int interval;
    // tmpFolder is the folder where the images are saved
    @ConfigProperty(name = "capture.tmpFolder") 
    String tmpFolder;
    // broker is the MQTT broker
    @ConfigProperty(name = "capture.brokerMqtt")
    String broker;
    // topic is the MQTT topic
    @ConfigProperty(name = "capture.topic")
    String topic;
    // nbImgSec is the number of images captured every second
    @ConfigProperty(name = "capture.periodicCapture")
    int periodicCapture;

    @ConfigProperty(name = "capture.saveImage")
    boolean saveImage;

    @ConfigProperty(name = "capture.videoDeviceIndex")
    int videoDeviceIndex;

    @ConfigProperty(name = "capture.mock")
    boolean mock;

    @ConfigProperty(name = "capture.videoPath")
    String videoPath;

    @ConfigProperty(name = "capture.videoPeriodicCapture")
    int videoPeriodicCapture;

    MqttPublisher mqttPublisher = null;

    private Long timerId;

    private volatile boolean stopRequested = false;
    private Thread testThread;





    private static final Logger LOGGER = Logger.getLogger(ScheduledCapture.class);
    Util util = null;
    // Start the camera when the application starts and set the resolution
    void onStart(@Observes StartupEvent ev) {
        Logger.getLogger(ScheduledCapture.class).info("The application is starting...");
        if(!mock){
            camera = new VideoCapture(videoDeviceIndex); 
            camera.set(Videoio.CAP_PROP_FRAME_WIDTH, 640); // Max resolution for Logitech C505
            camera.set(Videoio.CAP_PROP_FRAME_HEIGHT, 480); // Max resolution for Logitech C505
            //camera.set(Videoio.CAP_PROP_AUTOFOCUS, 0); // Try to disable autofocus
            //camera.set(Videoio.CAP_PROP_FOCUS, 255); // Try to disable autofocus
            camera.set(Videoio.CAP_PROP_EXPOSURE, 15); // Try to set exposure
        }
        util = new Util();
        mqttPublisher = new MqttPublisher(broker.trim(), topic.trim());

    }

    void readVideo(String videoPath) {        
        VideoCapture capture = new VideoCapture(videoPath);
        if (!capture.isOpened()) {
            throw new IllegalArgumentException("Video file not found at " + videoPath);
        }
        double fps = capture.get(Videoio.CAP_PROP_FPS);
        int frameSkip = (int) (fps/8);
        int count = 0;
        Mat frame = new Mat();
        while (!stopRequested) { // Continue reading the video until a stop request is received
            while (capture.read(frame)) {
                if (count % frameSkip == 0) {
                    // Publish the image to the MQTT broker
                    long timestamp = System.currentTimeMillis();
                    if(util != null) {
                        long start2 = System.nanoTime();
                        String jsonMessage = util.matToJson(frame, timestamp);
                        long end2 = System.nanoTime();
                        LOGGER.debugf("Time to convert image to json: %d ms", (end2 - start2) / 1000000);
                        LOGGER.debugf("JSON Message with id %s", jsonMessage);
                        try {
                            long start3 = System.nanoTime();
                            mqttPublisher.publish(jsonMessage);
                            long end3 = System.nanoTime();
                            LOGGER.debugf("Time to publish image: %d ms", (end3 - start3) / 1000000);
                            LOGGER.debugf("Message with id %s published to topic: %s", timestamp, topic);
                        } catch (MqttException e) {
                            e.printStackTrace();
                        }
                    }
                    if(saveImage){
                        String filepath = tmpFolder+"/" + timestamp + ".jpg";
                        imageService.saveImageAsync(frame, filepath).thenAccept(success -> {
                                if (success) {
                                    LOGGER.debug("Frame saved successfully");
                                } else {
                                    LOGGER.error("Failed to save frame");
                                }
                            });
                    }
                }
                count++;
                if (stopRequested) { // Check if stop has been requested inside the inner loop as well
                    break;
                }
            }
            capture.set(Videoio.CAP_PROP_POS_FRAMES, 0); // Reset the video to the first frame
        }
        capture.release();
    }

    // Capture and save a defined number of images every second
    void captureAndSaveImage() {
        LOGGER.debugf("The Thread name is %s" + Thread.currentThread().getName());
            // Capture the image
            long start = System.nanoTime();
            Mat image = imageCaptureService.captureImage(this.camera);
            long end = System.nanoTime();
            LOGGER.debugf("Time to capture image: %d ms", (end - start) / 1000000);
            // Publish the image to the MQTT broker
            long timestamp = System.currentTimeMillis();
            if(util != null) {
                long start2 = System.nanoTime();
                String jsonMessage = util.matToJson(image, timestamp);
                long end2 = System.nanoTime();
                LOGGER.debugf("Time to convert image to json: %d ms", (end2 - start2) / 1000000);
                LOGGER.debugf("JSON Message with id %s", jsonMessage);
                try {
                    long start3 = System.nanoTime();
                    mqttPublisher.publish(jsonMessage);
                       // Check if stop has been requested
                    if (stopRequested) {
                        // Stop capture and release camera
                        vertx.cancelTimer(timerId);
                        timerId = null;
                        imageCaptureService.releaseCamera(this.camera);
                        mqttPublisher.disconnect();
                        LOGGER.info("Capture stopped");
                        return;
                    }
                    long end3 = System.nanoTime();
                    LOGGER.debugf("Time to publish image: %d ms", (end3 - start3) / 1000000);
                    LOGGER.debugf("Message with id %s published to topic: %s", timestamp, topic);
                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }
            // Save the image to the file system (asynchronously)
            if(saveImage){
                String filepath = tmpFolder+"/" + timestamp + ".jpg";
                imageService.saveImageAsync(image, filepath).thenAccept(success -> {
                        if (success) {
                            LOGGER.debug("Image saved successfully");
                        } else {
                            LOGGER.error("Failed to save image");
                        }
                    });
            }
    }
    @POST
    @Path("/start")
    public Response start() {
        LOGGER.info("Capture started");
        stopRequested = false;
        mqttPublisher.connect();
        //captureEnabled = true;
        if (timerId != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Capture is already running").build();
        }
        timerId = vertx.setPeriodic(periodicCapture, id -> captureAndSaveImage());
        return Response.ok("Capture started").build();
    }
	
	@POST
    @Path("/connectReconnect")
    public Response connectReconnect() {
        LOGGER.info("connectReconnect started");
        stopRequested = false;
		// Do the command to restart the service here 
		const { exec } = require('child_process');
        exec('oc -n train rollout restart deployment/train-controller --kubeconfig=/var/lib/microshift/resources/kubeadmin/kubeconfig', (error, stdout, stderr) => {
			if (error) {
				onsole.error(`Execution Error: ${error.message}`);
				return;
			}
			if (stderr) {
				console.error(`Standard Error: ${stderr}`);
				return;
			}
			console.log(`Output:\n${stdout}`);
		});
        return Response.ok("Capture restarted ").build();
    }

    @POST
    @Path("/test")
    public Response test() {
        LOGGER.info("Test started");
        stopRequested = false;
        mqttPublisher.connect();
        if (timerId != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Capture is already running").build();
        }
        testThread = new Thread(() -> readVideo(videoPath));
        testThread.start();
        return Response.ok("read video from file started").build();
    }

    @POST
    @Path("/stop")
    public Response stop() {
        stopRequested = true;
        LOGGER.info("Stop requested");
        if (testThread != null) {
            try {
                testThread.join(); // Wait for the testThread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
            testThread = null;
        }
        return Response.ok("Stop requested").build();       
        // if (timerId == null) {
        //     return Response.status(Response.Status.BAD_REQUEST).entity("Capture is not running").build();
        // }else if(timerId != null){
        //     vertx.cancelTimer(timerId);
        //     timerId = null;
        // }
        // imageCaptureService.releaseCamera(this.camera);
        // LOGGER.info("Camera released");
        // mqttPublisher.disconnect();
        // LOGGER.info("MQTT disconnected");
    }
}