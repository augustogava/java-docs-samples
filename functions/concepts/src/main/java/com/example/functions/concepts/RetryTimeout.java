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

package com.example.functions.concepts;

// [START functions_tips_infinite_retries]

import com.example.functions.concepts.eventpojos.PubSubMessage;
import com.google.cloud.functions.BackgroundFunction;
import com.google.cloud.functions.Context;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.logging.Logger;

public class RetryTimeout implements BackgroundFunction<PubSubMessage> {
  private static final Logger LOGGER = Logger.getLogger(RetryTimeout.class.getName());
  private static final long MAX_EVENT_AGE = 10_000;

  // Use Gson (https://github.com/google/gson) to parse JSON content.
  private static final Gson gson = new Gson();

  /**
   * Background Cloud Function that only executes within
   * a certain time period after the triggering event
   */
  @Override
  public void accept(PubSubMessage message, Context context) {
    ZonedDateTime utcNow = ZonedDateTime.now(ZoneOffset.UTC);
    ZonedDateTime timestamp = utcNow;

    String data = message.getData();
    JsonObject body = gson.fromJson(data, JsonObject.class);
    if (body != null && body.has("timestamp")) {
      String tz = body.get("timestamp").getAsString();
      timestamp = ZonedDateTime.parse(tz);
    }
    long eventAge = Duration.between(timestamp, utcNow).toMillis();

    // Ignore events that are too old
    if (eventAge > MAX_EVENT_AGE) {
      LOGGER.info(String.format("Dropping event %s.", data));
      return;
    }

    // Process events that are recent enough
    LOGGER.info(String.format("Processing event %s.", data));
  }
}
// [END functions_tips_infinite_retries]
