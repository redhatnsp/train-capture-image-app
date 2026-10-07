# Demo Stop The Crazy Train - Capture app

![lego](https://www.lego.com/cdn/cs/set/assets/blt95604d8cc65e26c4/CITYtrain_Hero-XL-Desktop.png?fit=crop&format=webply&quality=80&width=1600&height=1000&dpr=1)

# Capture-App

Capture-App is a Quarkus service that grabs frames from the train's webcam, encodes them,
and publishes them over MQTT for Intelligent-Train to run the model against. It is the
first stage of the pipeline.

**It does not start capturing when it boots.** Capture runs only after something calls
`POST /capture/start`, which normally arrives from the monitoring app's button, routed
through Kafka and the second Camel route in `train-ceq-app`. A freshly started pod that
appears to be doing nothing is usually working as designed.

## Prerequisites

- **MQTT broker** — reachable at `MQTT_BROKER` (default `tcp://localhost:1883`). Every
  component talks to the same broker; the setup is documented once, in
  [train-controller](https://github.com/redhatnsp/train-controller#local-installation).
- **A camera**, unless running in mock mode (see [Test mode](#test-mode)).

OpenCV does not need to be installed separately — it comes from the
`io.quarkiverse.opencv:quarkus-opencv` extension declared in `pom.xml`.

## What it publishes

Frames go to the `train-image` topic as JSON:

```json
{ "id": 1728234567890, "image": "<base64>" }
```

`id` is `System.currentTimeMillis()`. The image is **WebP** at quality 80, despite the
`.jpg` extension used when `SAVE_IMAGE` is enabled.

## Configuration

Set through environment variables, resolved in `src/main/resources/application.properties`.

| Variable | Default | Purpose |
|---|---|---|
| `MQTT_BROKER` | `tcp://localhost:1883` | MQTT broker URL |
| `MQTT_TOPIC` | `train-image` | Topic frames are published to |
| `PERIODIC_CAPTURE` | `30` | Milliseconds between captures once started |
| `INTERVAL` | `100` | Capture interval property |
| `VIDE0_DEVICE_INDEX` | `0` | OpenCV camera index. **Note the spelling** — the property name contains a zero, not the letter O |
| `MOCK` | `false` | Skip camera initialisation and serve frames from a file instead |
| `VIDEO_PATH` | `/deployments/data/track-stop-and-slowdown.avi` | Video used by `/capture/test` |
| `VIDEO_PERIODIC_CAPTURE` | `30` | Frame pacing for the file reader |
| `SAVE_IMAGE` | `false` | Also write each frame to `TMP_FOLDER` |
| `TMP_FOLDER` | `/Users/mouchan/crazy-train-images` | Where frames are written when `SAVE_IMAGE` is on. The default is a leftover personal path; set it if you enable saving |
| `DROPBOX_TOKEN` | `null` | Unused by the capture flow |
| `LOGGER_LEVEL` | `INFO` | Root log level |

HTTP port is **8082 in dev mode** and **8080 in the container**.

## Endpoints

| Endpoint | Effect |
|---|---|
| `POST /capture/start` | Starts the periodic capture timer. Returns 400 if already running |
| `POST /capture/stop` | Requests a stop; the timer cancels itself on the next tick |
| `POST /capture/test` | Reads frames from `VIDEO_PATH` in a loop instead of the camera. Returns 400 if already running |
| `POST /capture/connectReconnect` | Restarts the `train-controller` deployment — see below |
| `POST /capture/startMovement` | Publishes command `2` (start train) to `train-command` |
| `POST /capture/stopMovement` | Publishes command `3` (stop train) to `train-command` |

### Train control endpoints

The last three automate steps that are otherwise done by hand over SSH during a demo.
They work by shelling out with `ProcessBuilder` rather than going through the
application's own MQTT client, which carries some caveats worth knowing:

- `connectReconnect` runs
  `oc -n train rollout restart deployment/train-controller --kubeconfig=/var/lib/microshift/resources/kubeadmin/kubeconfig`.
  **Neither Dockerfile installs `oc`**, so in a container this fails with
  `Cannot run program "oc"` — and the endpoint still returns **HTTP 200**. Check the pod
  logs rather than the response code to tell whether it worked.
- `startMovement` and `stopMovement` run `mosquitto_pub` against a **hardcoded
  `localhost:1883`**, ignoring `MQTT_BROKER`. They only reach the broker when it shares
  the pod's network namespace. The `mosquitto` package is installed by the Dockerfiles
  for this purpose.
- These endpoints are unauthenticated, and `connectReconnect` carries cluster-admin
  rights via that kubeconfig. Anything that can reach the service port can restart
  deployments.

Commands `2` and `3` exist only here — the model emits class ids `0` and `1` only, so
these are the sole producers of start/stop in the system.

## How to run

```sh
git clone https://github.com/redhatnsp/train-capture-image-app
cd train-capture-image-app/capture-app
./mvnw clean quarkus:dev
```

Dev mode needs JDK 17; the Maven wrapper supplies Maven itself.

### Test mode

The `%dev` profile sets `capture.mock=true` and points `VIDEO_PATH` at a clip bundled in
`src/main/resources/videos/`, so **`quarkus:dev` works with no webcam attached**. Use the
`test` endpoint rather than `start` in this mode — `start` drives the camera, which is
not initialised when mocking.

```sh
curl -X POST http://localhost:8082/capture/test    # read frames from the bundled video
curl -X POST http://localhost:8082/capture/stop
```

With a real camera:

```sh
curl -X POST http://localhost:8082/capture/start
curl -X POST http://localhost:8082/capture/stop
```

Swagger UI is enabled (`quarkus.swagger-ui.always-include=true`).

## Related Modules

- **intelligent-train** — consumes `train-image`, runs the model, publishes detections.
- **train-ceq-app** — turns detections into commands, and relays capture start/stop here.
- **train-monitoring-app** — displays the annotated frames.
- **train-controller** — drives the LEGO hub from the commands.

## Dependencies

- **Quarkus** — Kubernetes-native Java stack.
- **OpenCV** via `quarkus-opencv` — frame capture, resizing and WebP encoding.
- **Eclipse Paho MQTT client** — publishing frames.

Managed by Maven in `pom.xml`.

## Known issues

- `connectReconnect` returns HTTP 200 even when the command fails, and `oc` is not
  present in either image.
- The train control endpoints hardcode `localhost:1883` instead of using `MQTT_BROKER`.
- `Dockerfile.opencv` still builds from
  `quay.io/demo-ai-edge-crazy-train/openjdk-opencv:17-4.8.1`, in the old organisation.
- `TMP_FOLDER` defaults to a personal path.
- `Util.uploadToDropbox()` and `DropboxUploader` are unused but still pull the Dropbox
  SDK into the build.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](../LICENSE)
file for details.
