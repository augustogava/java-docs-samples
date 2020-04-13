/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.functions.imagemagick;

// [START functions_imagemagick_setup]
import com.example.functions.imagemagick.eventpojos.GcsEvent;
import com.google.cloud.functions.BackgroundFunction;
import com.google.cloud.functions.Context;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Feature.Type;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageSource;
import com.google.cloud.vision.v1.SafeSearchAnnotation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ImageMagick implements BackgroundFunction<GcsEvent> {

  private static Storage storage = StorageOptions.getDefaultInstance().getService();
  private static final String BLURRED_BUCKET_NAME = System.getenv("BLURRED_BUCKET_NAME");
  private static final Logger LOGGER = Logger.getLogger(ImageMagick.class.getName());
  // [END functions_imagemagick_setup]

  // [START functions_imagemagick_analyze]
  @Override
  // Blurs uploaded images that are flagged as Adult or Violence.
  public void accept(GcsEvent gcsEvent, Context context) {
    // Validate parameters
    if (gcsEvent.getBucket() == null || gcsEvent.getName() == null) {
      LOGGER.severe("Error: Malformed GCS event.");
      return;
    }

    BlobInfo blobInfo = BlobInfo.newBuilder(gcsEvent.getBucket(), gcsEvent.getName()).build();

    // Construct URI to GCS bucket and file.
    String gcsPath = String.format("gs://%s/%s", gcsEvent.getBucket(), gcsEvent.getName());
    LOGGER.info(String.format("Analyzing %s", gcsEvent.getName()));

    // Construct request.
    List<AnnotateImageRequest> requests = new ArrayList<>();
    ImageSource imgSource = ImageSource.newBuilder().setImageUri(gcsPath).build();
    Image img = Image.newBuilder().setSource(imgSource).build();
    Feature feature = Feature.newBuilder().setType(Type.SAFE_SEARCH_DETECTION).build();
    AnnotateImageRequest request =
        AnnotateImageRequest.newBuilder().addFeatures(feature).setImage(img).build();
    requests.add(request);

    // Send request to the Vision API.
    try (ImageAnnotatorClient client = ImageAnnotatorClient.create()) {
      BatchAnnotateImagesResponse response = client.batchAnnotateImages(requests);
      List<AnnotateImageResponse> responses = response.getResponsesList();
      for (AnnotateImageResponse res : responses) {
        if (res.hasError()) {
          LOGGER.info(String.format("Error: %s%n", res.getError().getMessage()));
          return;
        }
        // Get Safe Search Annotations
        SafeSearchAnnotation annotation = res.getSafeSearchAnnotation();
        if (annotation.getAdultValue() == 5 || annotation.getViolenceValue() == 5) {
          LOGGER.info(String.format("Detected %s as inappropriate.", gcsEvent.getName()));
          blur(blobInfo);
        } else {
          LOGGER.info(String.format("Detected %s as OK.", gcsEvent.getName()));
        }
      }
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Error with Vision API: " + e.getMessage(), e);
    }
  }
  // [END functions_imagemagick_analyze]

  // [START functions_imagemagick_blur]
  // Blurs the file described by blobInfo using ImageMagick,
  // and uploads it to the blurred bucket.
  private static void blur(BlobInfo blobInfo) throws IOException {
    String bucketName = blobInfo.getBucket();
    String fileName = blobInfo.getName();

    // Download image
    Blob blob = storage.get(BlobId.of(bucketName, fileName));
    Path download = Paths.get("/tmp/", fileName);
    blob.downloadTo(download);

    // Construct the command.
    List<String> args = new ArrayList<String>();
    args.add("convert");
    args.add(download.toString());
    args.add("-blur");
    args.add("0x8");
    Path upload = Paths.get("/tmp/", "blurred-" + fileName);
    args.add(upload.toString());
    try {
      ProcessBuilder pb = new ProcessBuilder(args);
      Process process = pb.start();
      process.waitFor();
    } catch (Exception e) {
      LOGGER.info(String.format("Error: %s", e.getMessage()));
    }

    // Upload image to blurred bucket.
    BlobId blurredBlobId = BlobId.of(BLURRED_BUCKET_NAME, fileName);
    BlobInfo blurredBlobInfo =
        BlobInfo.newBuilder(blurredBlobId).setContentType(blob.getContentType()).build();

    byte[] blurredFile = Files.readAllBytes(upload);
    storage.create(blurredBlobInfo, blurredFile);
    LOGGER.info(
        String.format("Blurred image uploaded to: gs://%s/%s", BLURRED_BUCKET_NAME, fileName));

    // Remove images from fileSystem
    Files.delete(download);
    Files.delete(upload);
  }
  // [END functions_imagemagick_blur]
  // [START functions_imagemagick_setup]
}
// [END functions_imagemagick_setup]
