package org.eluder.logback.ext.dynamodb.appender;

/*
 * #[license]
 * logback-ext-dynamodb-appender
 * %%
 * Copyright (C) 2014 - 2015 Tapio Rautonen
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * %[license]
 */

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.internal.InternalUtils;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import org.eluder.logback.ext.aws.core.AbstractAwsEncodingStringAppender;
import org.eluder.logback.ext.aws.core.AwsSupport;
import org.eluder.logback.ext.core.AppenderExecutors;
import org.eluder.logback.ext.aws.core.LoggingEventHandler;
import org.eluder.logback.ext.core.StringPayloadConverter;
import org.eluder.logback.ext.jackson.JacksonEncoder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static java.lang.String.format;

public class DynamoDbAppender extends AbstractAwsEncodingStringAppender<ILoggingEvent, String> {

    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
    private static final String DEFAULT_PRIMARY_KEY = "Id";
    private static final int DEFAULT_MAX_PAYLOAD_SIZE = 384;

    private String region;
    private String table;
    private String primaryKey = DEFAULT_PRIMARY_KEY;

    private AmazonDynamoDBAsyncClient dynamoDb;

    public DynamoDbAppender() {
        super();
        setMaxPayloadSize(DEFAULT_MAX_PAYLOAD_SIZE);
    }

    protected DynamoDbAppender(AwsSupport awsSupport, Filter<ILoggingEvent> sdkLoggingFilter) {
        super(awsSupport, sdkLoggingFilter);
        setMaxPayloadSize(DEFAULT_MAX_PAYLOAD_SIZE);
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    @Override
    public void start() {
        if (getEncoder() == null) {
            JacksonEncoder encoder = new JacksonEncoder();
            encoder.setFieldNames(new CapitalizingFieldNames());
            encoder.setTimeStampFormat(TIMESTAMP_FORMAT);
            setEncoder(encoder);
        }
        setConverter(new StringPayloadConverter(getCharset(), isBinary()));
        super.start();
    }

    @Override
    protected void doStart() {
        dynamoDb = new AmazonDynamoDBAsyncClient(
                getCredentials(),
                getClientConfiguration(),
                AppenderExecutors.newExecutor(this, getThreadPoolSize())
        );
        dynamoDb.setRegion(RegionUtils.getRegion(region));
    }

    @Override
    protected void doStop() {
        if (dynamoDb != null) {
            AppenderExecutors.shutdown(this, dynamoDb.getExecutorService(), getMaxFlushTime());
            dynamoDb.shutdown();
            dynamoDb = null;
        }
    }

    @Override
    protected void handle(final ILoggingEvent event, final String encoded) throws Exception {
        Item item = Item.fromJSON(encoded).withPrimaryKey(createEventId(event));
        Map<String, AttributeValue> attributes = InternalUtils.toAttributeValues(item);
        PutItemRequest request = new PutItemRequest(table, attributes);
        String errorMessage = format("Appender '%s' failed to send logging event '%s' to DynamoDB table '%s'", getName(), event, table);
        CountDownLatch latch = new CountDownLatch(isAsyncParent() ? 0 : 1);
        dynamoDb.putItemAsync(request, new LoggingEventHandler<PutItemRequest, PutItemResult>(this, latch, errorMessage));
        AppenderExecutors.awaitLatch(this, latch, getMaxFlushTime());
    }

    protected PrimaryKey createEventId(ILoggingEvent event) {
        return new PrimaryKey(primaryKey, UUID.randomUUID().toString());
    }

}
