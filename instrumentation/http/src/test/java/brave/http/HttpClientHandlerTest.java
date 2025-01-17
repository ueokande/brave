/*
 * Copyright 2013-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.http;

import brave.ScopedSpan;
import brave.SpanCustomizer;
import brave.Tracing;
import brave.http.HttpClientRequest.FromHttpAdapter;
import brave.http.HttpClientRequest.ToHttpAdapter;
import brave.propagation.TraceContext;
import brave.sampler.SamplerFunction;
import brave.sampler.SamplerFunctions;
import brave.sampler.Sampler;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class HttpClientHandlerTest {
  List<Span> spans = new ArrayList<>();
  @Mock HttpSampler sampler;
  HttpTracing httpTracing;
  HttpClientHandler<Object, Object> handler;

  @Spy HttpClientParser parser = new HttpClientParser();
  @Mock HttpClientAdapter<Object, Object> adapter;
  @Mock TraceContext.Injector<Object> injector;
  @Mock Object request;
  @Mock Object response;

  @Mock SamplerFunction<HttpRequest> requestSampler;
  HttpTracing defaultHttpTracing;
  HttpClientHandler<HttpClientRequest, HttpClientResponse> defaultHandler;

  @Mock(answer = CALLS_REAL_METHODS) HttpClientRequest defaultRequest;
  @Mock(answer = CALLS_REAL_METHODS) HttpClientResponse defaultResponse;

  @Before public void init() {
    httpTracing = HttpTracing.newBuilder(Tracing.newBuilder().spanReporter(spans::add).build())
      .clientSampler(sampler).clientParser(parser).build();
    handler = HttpClientHandler.create(httpTracing, adapter);

    defaultHttpTracing =
      HttpTracing.newBuilder(Tracing.newBuilder().spanReporter(spans::add).build())
        .clientSampler(requestSampler).clientParser(parser).build();
    defaultHandler = HttpClientHandler.create(defaultHttpTracing);

    when(adapter.method(request)).thenReturn("GET");

    when(defaultRequest.unwrap()).thenReturn(request);
    when(defaultResponse.unwrap()).thenReturn(response);
  }

  @After public void close() {
    Tracing.current().close();
  }

  @Test public void handleStart_parsesTagsWithCustomizer() {
    brave.Span span = mock(brave.Span.class);
    brave.SpanCustomizer spanCustomizer = mock(brave.SpanCustomizer.class);
    when(adapter.method(request)).thenReturn("GET");
    when(span.customizer()).thenReturn(spanCustomizer);

    handler.handleStart(adapter, request, span);

    verify(spanCustomizer).name("GET");
    verify(spanCustomizer).tag("http.method", "GET");
    verifyNoMoreInteractions(spanCustomizer);
  }

  @Test public void handleSend_defaultsToMakeNewTrace() {
    when(sampler.trySample(any(FromHttpAdapter.class))).thenReturn(null);

    assertThat(handler.handleSend(injector, request))
      .extracting(brave.Span::isNoop, s -> s.context().parentId())
      .containsExactly(false, null);
  }

  @Test public void handleSend_makesAChild() {
    ScopedSpan parent = httpTracing.tracing().tracer().startScopedSpan("test");
    try {
      assertThat(handler.handleSend(injector, request))
        .extracting(brave.Span::isNoop, s -> s.context().parentId())
        .containsExactly(false, parent.context().spanId());
    } finally {
      parent.finish();
    }
  }

  @Test public void handleSend_makesRequestBasedSamplingDecision() {
    // request sampler says false eventhough trace ID sampler would have said true
    when(sampler.trySample(any(FromHttpAdapter.class))).thenReturn(false);

    assertThat(handler.handleSend(injector, request).isNoop())
      .isTrue();
  }

  @Test public void handleSend_injectsTheTraceContext() {
    TraceContext context = handler.handleSend(injector, request).context();

    verify(injector).inject(context, request);
  }

  @Test public void handleSend_injectsTheTraceContext_onTheCarrier() {
    HttpClientRequest customCarrier = mock(HttpClientRequest.class);
    TraceContext context = handler.handleSend(injector, customCarrier, request).context();

    verify(injector).inject(context, customCarrier);
  }

  @Test public void handleSend_addsClientAddressWhenOnlyServiceName() {
    when(sampler.trySample(any(FromHttpAdapter.class))).thenReturn(null);

    httpTracing = httpTracing.clientOf("remote-service");

    HttpClientHandler.create(httpTracing, adapter).handleSend(injector, request).finish();

    assertThat(spans)
      .extracting(Span::remoteServiceName)
      .containsExactly("remote-service");
  }

  @Test public void handleSend_skipsClientAddressWhenUnparsed() {
    when(sampler.trySample(any(FromHttpAdapter.class))).thenReturn(null);

    handler.handleSend(injector, request).finish();

    assertThat(spans)
      .extracting(Span::remoteServiceName)
      .containsNull();
  }

  @Test public void externalTimestamps() {
    when(requestSampler.trySample(defaultRequest)).thenReturn(null);
    when(defaultRequest.startTimestamp()).thenReturn(123000L);
    when(defaultResponse.finishTimestamp()).thenReturn(124000L);

    brave.Span span = defaultHandler.handleSend(defaultRequest);
    defaultHandler.handleReceive(defaultResponse, null, span);

    assertThat(spans.get(0).durationAsLong()).isEqualTo(1000L);
  }

  @Test public void handleSend_traceIdSamplerSpecialCased() {
    Sampler sampler = mock(Sampler.class);

    defaultHandler =
      HttpClientHandler.create(HttpTracing.newBuilder(Tracing.newBuilder().sampler(sampler).build())
        .clientSampler(SamplerFunctions.deferDecision()).build());

    assertThat(defaultHandler.handleSend(defaultRequest).isNoop()).isTrue();

    verify(sampler).isSampled(anyLong());
  }

  @Test public void handleSend_neverSamplerSpecialCased() {
    Sampler sampler = mock(Sampler.class);

    defaultHandler =
      HttpClientHandler.create(HttpTracing.newBuilder(Tracing.newBuilder().sampler(sampler).build())
        .clientSampler(SamplerFunctions.neverSample()).build());

    assertThat(defaultHandler.handleSend(defaultRequest).isNoop()).isTrue();

    verifyNoMoreInteractions(sampler);
  }

  @Test public void handleSend_samplerSeesHttpClientRequest() {
    defaultHandler.handleSend(defaultRequest);

    verify(requestSampler).trySample(defaultRequest);
  }

  @Test public void nextSpan_samplerSeesHttpClientRequest() {
    defaultHandler.nextSpan(defaultRequest);

    verify(requestSampler).trySample(defaultRequest);
  }

  @Test public void nextSpan_samplerSeesUnwrappedType_oldHandler() {
    when(sampler.trySample(defaultRequest)).thenReturn(null);

    handler.nextSpan(defaultRequest);

    verify(sampler).trySample(defaultRequest);
  }

  @Test public void handleSend_parserSeesUnwrappedType() {
    when(requestSampler.trySample(defaultRequest)).thenReturn(null);

    brave.Span span = defaultHandler.handleSend(defaultRequest);
    defaultHandler.handleReceive(defaultResponse, null, span);

    verify(parser).request(any(ToHttpAdapter.class), eq(request), any(SpanCustomizer.class));
  }

  @Test public void handleReceive_oldHandler() {
    when(sampler.trySample(any(FromHttpAdapter.class))).thenReturn(null);

    brave.Span span = handler.handleSend(injector, adapter, request);
    handler.handleReceive(response, null, span);

    verify(parser).response(eq(adapter), eq(response), isNull(), any(SpanCustomizer.class));
  }

  @Test public void handleReceive_parserSeesUnwrappedType() {
    when(requestSampler.trySample(defaultRequest)).thenReturn(null);

    brave.Span span = defaultHandler.handleSend(defaultRequest);
    defaultHandler.handleReceive(defaultResponse, null, span);

    verify(parser).request(any(ToHttpAdapter.class), eq(request), any(SpanCustomizer.class));
  }

  @Test public void handleReceive_parserSeesUnwrappedType_oldHandler() {
    when(sampler.trySample(defaultRequest)).thenReturn(null);

    brave.Span span = handler.handleSend(defaultRequest);
    handler.handleReceive(defaultResponse, null, span);

    HttpClientResponse.Adapter adapter = new HttpClientResponse.Adapter(defaultResponse);
    verify(parser).response(eq(adapter), eq(response), isNull(), any(SpanCustomizer.class));
  }
}
