/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.core;

import static io.rsocket.core.PayloadValidationUtils.INVALID_PAYLOAD_ERROR_MESSAGE;
import static io.rsocket.frame.FrameHeaderFlyweight.frameType;
import static io.rsocket.frame.FrameType.CANCEL;
import static io.rsocket.frame.FrameType.REQUEST_CHANNEL;
import static io.rsocket.frame.FrameType.REQUEST_RESPONSE;
import static io.rsocket.frame.FrameType.REQUEST_STREAM;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCounted;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.buffer.LeaksTrackingByteBufAllocator;
import io.rsocket.exceptions.ApplicationErrorException;
import io.rsocket.exceptions.CustomRSocketException;
import io.rsocket.exceptions.RejectedSetupException;
import io.rsocket.frame.CancelFrameFlyweight;
import io.rsocket.frame.ErrorFrameFlyweight;
import io.rsocket.frame.FrameHeaderFlyweight;
import io.rsocket.frame.FrameLengthFlyweight;
import io.rsocket.frame.FrameType;
import io.rsocket.frame.PayloadFrameFlyweight;
import io.rsocket.frame.RequestChannelFrameFlyweight;
import io.rsocket.frame.RequestNFrameFlyweight;
import io.rsocket.frame.RequestStreamFrameFlyweight;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.internal.subscriber.AssertSubscriber;
import io.rsocket.lease.RequesterLeaseHandler;
import io.rsocket.test.util.TestSubscriber;
import io.rsocket.util.ByteBufPayload;
import io.rsocket.util.DefaultPayload;
import io.rsocket.util.EmptyPayload;
import io.rsocket.util.MultiSubscriberRSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.runners.model.Statement;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.util.RaceTestUtils;

public class RSocketRequesterTest {

  ClientSocketRule rule;

  @BeforeEach
  public void setUp() throws Throwable {
    rule = new ClientSocketRule();
    rule.apply(
            new Statement() {
              @Override
              public void evaluate() {}
            },
            null)
        .evaluate();
  }

  @Test
  @Timeout(2_000)
  public void testInvalidFrameOnStream0() {
    rule.connection.addToReceivedBuffer(RequestNFrameFlyweight.encode(rule.alloc(), 0, 10));
    assertThat("Unexpected errors.", rule.errors, hasSize(1));
    assertThat(
        "Unexpected error received.",
        rule.errors,
        contains(instanceOf(IllegalStateException.class)));
    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testStreamInitialN() {
    Flux<Payload> stream = rule.socket.requestStream(EmptyPayload.INSTANCE);

    BaseSubscriber<Payload> subscriber =
        new BaseSubscriber<Payload>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {
            // don't request here
          }
        };
    stream.subscribe(subscriber);

    Assertions.assertThat(rule.connection.getSent()).isEmpty();

    subscriber.request(5);

    List<ByteBuf> sent = new ArrayList<>(rule.connection.getSent());

    assertThat("sent frame count", sent.size(), is(1));

    ByteBuf f = sent.get(0);

    assertThat("initial frame", frameType(f), is(REQUEST_STREAM));
    assertThat("initial request n", RequestStreamFrameFlyweight.initialRequestN(f), is(5));
    assertThat("should be released", f.release(), is(true));
    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testHandleSetupException() {
    rule.connection.addToReceivedBuffer(
        ErrorFrameFlyweight.encode(rule.alloc(), 0, new RejectedSetupException("boom")));
    assertThat("Unexpected errors.", rule.errors, hasSize(1));
    assertThat(
        "Unexpected error received.",
        rule.errors,
        contains(instanceOf(RejectedSetupException.class)));
    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testHandleApplicationException() {
    rule.connection.clearSendReceiveBuffers();
    Publisher<Payload> response = rule.socket.requestResponse(EmptyPayload.INSTANCE);
    Subscriber<Payload> responseSub = TestSubscriber.create();
    response.subscribe(responseSub);

    int streamId = rule.getStreamIdForRequestType(REQUEST_RESPONSE);
    rule.connection.addToReceivedBuffer(
        ErrorFrameFlyweight.encode(rule.alloc(), streamId, new ApplicationErrorException("error")));

    verify(responseSub).onError(any(ApplicationErrorException.class));

    Assertions.assertThat(rule.connection.getSent())
        // requestResponseFrame
        .hasSize(1)
        .allMatch(ReferenceCounted::release);

    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testHandleValidFrame() {
    Publisher<Payload> response = rule.socket.requestResponse(EmptyPayload.INSTANCE);
    Subscriber<Payload> sub = TestSubscriber.create();
    response.subscribe(sub);

    int streamId = rule.getStreamIdForRequestType(REQUEST_RESPONSE);
    rule.connection.addToReceivedBuffer(
        PayloadFrameFlyweight.encodeNext(rule.alloc(), streamId, EmptyPayload.INSTANCE));

    verify(sub).onComplete();
    Assertions.assertThat(rule.connection.getSent()).hasSize(1).allMatch(ReferenceCounted::release);
    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testRequestReplyWithCancel() {
    Mono<Payload> response = rule.socket.requestResponse(EmptyPayload.INSTANCE);

    try {
      response.block(Duration.ofMillis(100));
    } catch (IllegalStateException ise) {
    }

    List<ByteBuf> sent = new ArrayList<>(rule.connection.getSent());

    assertThat(
        "Unexpected frame sent on the connection.", frameType(sent.get(0)), is(REQUEST_RESPONSE));
    assertThat("Unexpected frame sent on the connection.", frameType(sent.get(1)), is(CANCEL));
    Assertions.assertThat(sent).hasSize(2).allMatch(ReferenceCounted::release);
    rule.assertHasNoLeaks();
  }

  @Test
  @Disabled("invalid")
  @Timeout(2_000)
  public void testRequestReplyErrorOnSend() {
    rule.connection.setAvailability(0); // Fails send
    Mono<Payload> response = rule.socket.requestResponse(EmptyPayload.INSTANCE);
    Subscriber<Payload> responseSub = TestSubscriber.create(10);
    response.subscribe(responseSub);

    this.rule.assertNoConnectionErrors();

    verify(responseSub).onSubscribe(any(Subscription.class));

    rule.assertHasNoLeaks();
    // TODO this should get the error reported through the response subscription
    //    verify(responseSub).onError(any(RuntimeException.class));
  }

  @Test
  @Timeout(2_000)
  public void testLazyRequestResponse() {
    Publisher<Payload> response =
        new MultiSubscriberRSocket(rule.socket).requestResponse(EmptyPayload.INSTANCE);
    int streamId = sendRequestResponse(response);
    Assertions.assertThat(rule.connection.getSent()).hasSize(1).allMatch(ReferenceCounted::release);
    rule.assertHasNoLeaks();
    rule.connection.clearSendReceiveBuffers();
    int streamId2 = sendRequestResponse(response);
    assertThat("Stream ID reused.", streamId2, not(equalTo(streamId)));
    Assertions.assertThat(rule.connection.getSent()).hasSize(1).allMatch(ReferenceCounted::release);
    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testChannelRequestCancellation() {
    MonoProcessor<Void> cancelled = MonoProcessor.create();
    Flux<Payload> request = Flux.<Payload>never().doOnCancel(cancelled::onComplete);
    rule.socket.requestChannel(request).subscribe().dispose();
    Flux.first(
            cancelled,
            Flux.error(new IllegalStateException("Channel request not cancelled"))
                .delaySubscription(Duration.ofSeconds(1)))
        .blockFirst();
    rule.assertHasNoLeaks();
  }

  @Test
  @Timeout(2_000)
  public void testChannelRequestCancellation2() {
    MonoProcessor<Void> cancelled = MonoProcessor.create();
    Flux<Payload> request =
        Flux.<Payload>just(EmptyPayload.INSTANCE).repeat(259).doOnCancel(cancelled::onComplete);
    rule.socket.requestChannel(request).subscribe().dispose();
    Flux.first(
            cancelled,
            Flux.error(new IllegalStateException("Channel request not cancelled"))
                .delaySubscription(Duration.ofSeconds(1)))
        .blockFirst();
    Assertions.assertThat(rule.connection.getSent()).allMatch(ReferenceCounted::release);
    rule.assertHasNoLeaks();
  }

  @Test
  public void testChannelRequestServerSideCancellation() {
    MonoProcessor<Payload> cancelled = MonoProcessor.create();
    UnicastProcessor<Payload> request = UnicastProcessor.create();
    request.onNext(EmptyPayload.INSTANCE);
    rule.socket.requestChannel(request).subscribe(cancelled);
    int streamId = rule.getStreamIdForRequestType(REQUEST_CHANNEL);
    rule.connection.addToReceivedBuffer(CancelFrameFlyweight.encode(rule.alloc(), streamId));
    rule.connection.addToReceivedBuffer(
        PayloadFrameFlyweight.encodeComplete(rule.alloc(), streamId));
    Flux.first(
            cancelled,
            Flux.error(new IllegalStateException("Channel request not cancelled"))
                .delaySubscription(Duration.ofSeconds(1)))
        .blockFirst();

    Assertions.assertThat(request.isDisposed()).isTrue();
    Assertions.assertThat(rule.connection.getSent())
        .hasSize(1)
        .first()
        .matches(bb -> frameType(bb) == REQUEST_CHANNEL)
        .matches(ReferenceCounted::release);
    rule.assertHasNoLeaks();
  }

  @Test
  public void testCorrectFrameOrder() {
    MonoProcessor<Object> delayer = MonoProcessor.create();
    BaseSubscriber<Payload> subscriber =
        new BaseSubscriber<Payload>() {
          @Override
          protected void hookOnSubscribe(Subscription subscription) {}
        };
    rule.socket
        .requestChannel(
            Flux.concat(Flux.just(0).delayUntil(i -> delayer), Flux.range(1, 999))
                .map(i -> DefaultPayload.create(i + "")))
        .subscribe(subscriber);

    subscriber.request(1);
    subscriber.request(Long.MAX_VALUE);
    delayer.onComplete();

    Iterator<ByteBuf> iterator = rule.connection.getSent().iterator();

    ByteBuf initialFrame = iterator.next();

    Assertions.assertThat(FrameHeaderFlyweight.frameType(initialFrame)).isEqualTo(REQUEST_CHANNEL);
    Assertions.assertThat(RequestChannelFrameFlyweight.initialRequestN(initialFrame))
        .isEqualTo(Integer.MAX_VALUE);
    Assertions.assertThat(
            RequestChannelFrameFlyweight.data(initialFrame).toString(CharsetUtil.UTF_8))
        .isEqualTo("0");
    Assertions.assertThat(initialFrame.release()).isTrue();

    Assertions.assertThat(iterator.hasNext()).isFalse();
    rule.assertHasNoLeaks();
  }

  @Test
  public void shouldThrownExceptionIfGivenPayloadIsExitsSizeAllowanceWithNoFragmentation() {
    prepareCalls()
        .forEach(
            generator -> {
              byte[] metadata = new byte[FrameLengthFlyweight.FRAME_LENGTH_MASK];
              byte[] data = new byte[FrameLengthFlyweight.FRAME_LENGTH_MASK];
              ThreadLocalRandom.current().nextBytes(metadata);
              ThreadLocalRandom.current().nextBytes(data);
              StepVerifier.create(
                      generator.apply(rule.socket, DefaultPayload.create(data, metadata)))
                  .expectSubscription()
                  .expectErrorSatisfies(
                      t ->
                          Assertions.assertThat(t)
                              .isInstanceOf(IllegalArgumentException.class)
                              .hasMessage(INVALID_PAYLOAD_ERROR_MESSAGE))
                  .verify();
              rule.assertHasNoLeaks();
            });
  }

  static Stream<BiFunction<RSocket, Payload, Publisher<?>>> prepareCalls() {
    return Stream.of(
        RSocket::fireAndForget,
        RSocket::requestResponse,
        RSocket::requestStream,
        (rSocket, payload) -> rSocket.requestChannel(Flux.just(payload)),
        RSocket::metadataPush);
  }

  @Test
  public void
      shouldThrownExceptionIfGivenPayloadIsExitsSizeAllowanceWithNoFragmentationForRequestChannelCase() {
    byte[] metadata = new byte[FrameLengthFlyweight.FRAME_LENGTH_MASK];
    byte[] data = new byte[FrameLengthFlyweight.FRAME_LENGTH_MASK];
    ThreadLocalRandom.current().nextBytes(metadata);
    ThreadLocalRandom.current().nextBytes(data);
    StepVerifier.create(
            rule.socket.requestChannel(
                Flux.just(EmptyPayload.INSTANCE, DefaultPayload.create(data, metadata))))
        .expectSubscription()
        .then(
            () ->
                rule.connection.addToReceivedBuffer(
                    RequestNFrameFlyweight.encode(
                        rule.alloc(), rule.getStreamIdForRequestType(REQUEST_CHANNEL), 2)))
        .expectErrorSatisfies(
            t ->
                Assertions.assertThat(t)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(INVALID_PAYLOAD_ERROR_MESSAGE))
        .verify();
    Assertions.assertThat(rule.connection.getSent())
        // expect to be sent RequestChannelFrame
        // expect to be sent CancelFrame
        .hasSize(2)
        .allMatch(ReferenceCounted::release);
    rule.assertHasNoLeaks();
  }

  @Test
  @Disabled("Due to https://github.com/reactor/reactor-core/pull/2114")
  @SuppressWarnings("unchecked")
  public void checkNoLeaksOnRacingTest() {

    racingCases()
        .forEach(
            a -> {
              ((Runnable) a.get()[0]).run();
              checkNoLeaksOnRacing(
                  (Function<ClientSocketRule, Publisher<Payload>>) a.get()[1],
                  (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>) a.get()[2]);
            });
  }

  public void checkNoLeaksOnRacing(
      Function<ClientSocketRule, Publisher<Payload>> initiator,
      BiConsumer<AssertSubscriber<Payload>, ClientSocketRule> runner) {
    for (int i = 0; i < 10000; i++) {
      ClientSocketRule clientSocketRule = new ClientSocketRule();
      try {
        clientSocketRule
            .apply(
                new Statement() {
                  @Override
                  public void evaluate() {}
                },
                null)
            .evaluate();
      } catch (Throwable throwable) {
        throwable.printStackTrace();
      }

      Publisher<Payload> payloadP = initiator.apply(clientSocketRule);
      AssertSubscriber<Payload> assertSubscriber = AssertSubscriber.create();

      if (payloadP instanceof Flux) {
        ((Flux<Payload>) payloadP).doOnNext(Payload::release).subscribe(assertSubscriber);
      } else {
        ((Mono<Payload>) payloadP).doOnNext(Payload::release).subscribe(assertSubscriber);
      }

      runner.accept(assertSubscriber, clientSocketRule);

      Assertions.assertThat(clientSocketRule.connection.getSent())
          .allMatch(ReferenceCounted::release);

      rule.assertHasNoLeaks();
    }
  }

  private static Stream<Arguments> racingCases() {
    return Stream.of(
        Arguments.of(
            (Runnable) () -> System.out.println("RequestStream downstream cancellation case"),
            (Function<ClientSocketRule, Publisher<Payload>>)
                (rule) -> rule.socket.requestStream(EmptyPayload.INSTANCE),
            (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>)
                (as, rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  ByteBuf metadata = allocator.buffer();
                  metadata.writeCharSequence("abc", CharsetUtil.UTF_8);
                  ByteBuf data = allocator.buffer();
                  data.writeCharSequence("def", CharsetUtil.UTF_8);
                  int streamId = rule.getStreamIdForRequestType(REQUEST_STREAM);
                  ByteBuf frame =
                      PayloadFrameFlyweight.encode(
                          allocator, streamId, false, false, true, metadata, data);

                  RaceTestUtils.race(as::cancel, () -> rule.connection.addToReceivedBuffer(frame));
                }),
        Arguments.of(
            (Runnable) () -> System.out.println("RequestChannel downstream cancellation case"),
            (Function<ClientSocketRule, Publisher<Payload>>)
                (rule) -> rule.socket.requestChannel(Flux.just(EmptyPayload.INSTANCE)),
            (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>)
                (as, rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  ByteBuf metadata = allocator.buffer();
                  metadata.writeCharSequence("abc", CharsetUtil.UTF_8);
                  ByteBuf data = allocator.buffer();
                  data.writeCharSequence("def", CharsetUtil.UTF_8);
                  int streamId = rule.getStreamIdForRequestType(REQUEST_CHANNEL);
                  ByteBuf frame =
                      PayloadFrameFlyweight.encode(
                          allocator, streamId, false, false, true, metadata, data);

                  RaceTestUtils.race(as::cancel, () -> rule.connection.addToReceivedBuffer(frame));
                }),
        Arguments.of(
            (Runnable) () -> System.out.println("RequestChannel upstream cancellation 1"),
            (Function<ClientSocketRule, Publisher<Payload>>)
                (rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  ByteBuf metadata = allocator.buffer();
                  metadata.writeCharSequence("abc", CharsetUtil.UTF_8);
                  ByteBuf data = allocator.buffer();
                  data.writeCharSequence("def", CharsetUtil.UTF_8);
                  return rule.socket.requestChannel(
                      Flux.just(ByteBufPayload.create(data, metadata)));
                },
            (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>)
                (as, rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  int streamId = rule.getStreamIdForRequestType(REQUEST_CHANNEL);
                  ByteBuf frame = CancelFrameFlyweight.encode(allocator, streamId);

                  RaceTestUtils.race(
                      () -> as.request(1), () -> rule.connection.addToReceivedBuffer(frame));
                }),
        Arguments.of(
            (Runnable) () -> System.out.println("RequestChannel upstream cancellation 2"),
            (Function<ClientSocketRule, Publisher<Payload>>)
                (rule) ->
                    rule.socket.requestChannel(
                        Flux.generate(
                            () -> 1L,
                            (index, sink) -> {
                              final Payload payload =
                                  ByteBufPayload.create("d" + index, "m" + index);
                              sink.next(payload);
                              return ++index;
                            })),
            (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>)
                (as, rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  int streamId = rule.getStreamIdForRequestType(REQUEST_CHANNEL);
                  ByteBuf frame = CancelFrameFlyweight.encode(allocator, streamId);

                  as.request(1);

                  RaceTestUtils.race(
                      () -> as.request(Long.MAX_VALUE),
                      () -> rule.connection.addToReceivedBuffer(frame));
                }),
        Arguments.of(
            (Runnable) () -> System.out.println("RequestChannel remote error"),
            (Function<ClientSocketRule, Publisher<Payload>>)
                (rule) ->
                    rule.socket.requestChannel(
                        Flux.generate(
                            () -> 1L,
                            (index, sink) -> {
                              final Payload payload =
                                  ByteBufPayload.create("d" + index, "m" + index);
                              sink.next(payload);
                              return ++index;
                            })),
            (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>)
                (as, rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  int streamId = rule.getStreamIdForRequestType(REQUEST_CHANNEL);
                  ByteBuf frame =
                      ErrorFrameFlyweight.encode(allocator, streamId, new RuntimeException("test"));

                  as.request(1);

                  RaceTestUtils.race(
                      () -> as.request(Long.MAX_VALUE),
                      () -> rule.connection.addToReceivedBuffer(frame));
                }),
        Arguments.of(
            (Runnable) () -> System.out.println("RequestResponse downstream cancellation"),
            (Function<ClientSocketRule, Publisher<Payload>>)
                (rule) -> rule.socket.requestResponse(EmptyPayload.INSTANCE),
            (BiConsumer<AssertSubscriber<Payload>, ClientSocketRule>)
                (as, rule) -> {
                  ByteBufAllocator allocator = rule.alloc();
                  ByteBuf metadata = allocator.buffer();
                  metadata.writeCharSequence("abc", CharsetUtil.UTF_8);
                  ByteBuf data = allocator.buffer();
                  data.writeCharSequence("def", CharsetUtil.UTF_8);
                  int streamId = rule.getStreamIdForRequestType(REQUEST_RESPONSE);
                  ByteBuf frame =
                      PayloadFrameFlyweight.encode(
                          allocator, streamId, false, false, true, metadata, data);

                  RaceTestUtils.race(as::cancel, () -> rule.connection.addToReceivedBuffer(frame));
                }));
  }

  @Test
  public void simpleOnDiscardRequestChannelTest() {
    AssertSubscriber<Payload> assertSubscriber = AssertSubscriber.create(1);
    TestPublisher<Payload> testPublisher = TestPublisher.create();

    Flux<Payload> payloadFlux = rule.socket.requestChannel(testPublisher);

    payloadFlux.subscribe(assertSubscriber);

    testPublisher.next(
        ByteBufPayload.create("d", "m"),
        ByteBufPayload.create("d1", "m1"),
        ByteBufPayload.create("d2", "m2"));

    assertSubscriber.cancel();

    Assertions.assertThat(rule.connection.getSent()).allMatch(ByteBuf::release);

    rule.assertHasNoLeaks();
  }

  @Test
  public void simpleOnDiscardRequestChannelTest2() {
    ByteBufAllocator allocator = rule.alloc();
    AssertSubscriber<Payload> assertSubscriber = AssertSubscriber.create(1);
    TestPublisher<Payload> testPublisher = TestPublisher.create();

    Flux<Payload> payloadFlux = rule.socket.requestChannel(testPublisher);

    payloadFlux.subscribe(assertSubscriber);

    testPublisher.next(ByteBufPayload.create("d", "m"));

    int streamId = rule.getStreamIdForRequestType(REQUEST_CHANNEL);
    testPublisher.next(ByteBufPayload.create("d1", "m1"), ByteBufPayload.create("d2", "m2"));

    rule.connection.addToReceivedBuffer(
        ErrorFrameFlyweight.encode(
            allocator, streamId, new CustomRSocketException(0x00000404, "test")));

    Assertions.assertThat(rule.connection.getSent()).allMatch(ByteBuf::release);

    rule.assertHasNoLeaks();
  }

  public int sendRequestResponse(Publisher<Payload> response) {
    Subscriber<Payload> sub = TestSubscriber.create();
    response.subscribe(sub);
    int streamId = rule.getStreamIdForRequestType(REQUEST_RESPONSE);
    rule.connection.addToReceivedBuffer(
        PayloadFrameFlyweight.encodeNextComplete(rule.alloc(), streamId, EmptyPayload.INSTANCE));
    verify(sub).onNext(any(Payload.class));
    verify(sub).onComplete();
    return streamId;
  }

  public static class ClientSocketRule extends AbstractSocketRule<RSocketRequester> {
    @Override
    protected RSocketRequester newRSocket(LeaksTrackingByteBufAllocator allocator) {
      return new RSocketRequester(
          allocator,
          connection,
          PayloadDecoder.ZERO_COPY,
          throwable -> errors.add(throwable),
          StreamIdSupplier.clientSupplier(),
          0,
          0,
          0,
          null,
          RequesterLeaseHandler.None);
    }

    public int getStreamIdForRequestType(FrameType expectedFrameType) {
      assertThat("Unexpected frames sent.", connection.getSent(), hasSize(greaterThanOrEqualTo(1)));
      List<FrameType> framesFound = new ArrayList<>();
      for (ByteBuf frame : connection.getSent()) {
        FrameType frameType = frameType(frame);
        if (frameType == expectedFrameType) {
          return FrameHeaderFlyweight.streamId(frame);
        }
        framesFound.add(frameType);
      }
      throw new AssertionError(
          "No frames sent with frame type: "
              + expectedFrameType
              + ", frames found: "
              + framesFound);
    }
  }
}
