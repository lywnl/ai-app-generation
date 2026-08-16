package dev.langchain4j.model.openai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingRequestHandle;
import dev.langchain4j.model.openai.internal.ErrorHandling;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.ResponseHandle;
import dev.langchain4j.model.openai.internal.StreamingCompletionHandling;
import dev.langchain4j.model.openai.internal.StreamingResponseHandling;
import dev.langchain4j.model.openai.internal.SyncOrAsyncOrStreaming;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiStreamingChatModelTest {

    @Test
    void adaptsResponseHandleCancellationToStreamingHandler() {
        java.util.concurrent.atomic.AtomicInteger cancellations =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<
                dev.langchain4j.model.chat.response.StreamingRequestHandle> handle =
                new java.util.concurrent.atomic.AtomicReference<>();
        dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler =
                new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                    @Override
                    public void onRequestHandle(
                            dev.langchain4j.model.chat.response.StreamingRequestHandle value) {
                        handle.set(value);
                    }

                    @Override
                    public void onPartialResponse(String partialResponse) {
                    }

                    @Override
                    public void onCompleteResponse(
                            dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }
                };

        OpenAiStreamingChatModel.registerRequestHandle(
                handler, cancellations::incrementAndGet);
        handle.get().cancel();

        assertEquals(1, cancellations.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doChatPublishesRealExecuteHandleAndUnsupportedCancellationDoesNotEscape() {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey("test-key").modelName("test-model").build();
        OpenAiClient client = mock(OpenAiClient.class);
        SyncOrAsyncOrStreaming<dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse>
                request = mock(SyncOrAsyncOrStreaming.class);
        StreamingResponseHandling partial = mock(StreamingResponseHandling.class);
        StreamingCompletionHandling completion = mock(StreamingCompletionHandling.class);
        ErrorHandling errors = mock(ErrorHandling.class);
        ResponseHandle responseHandle = mock(ResponseHandle.class);
        when(client.chatCompletion(any())).thenReturn(request);
        when(request.onPartialResponse(any())).thenReturn(partial);
        when(partial.onComplete(any())).thenReturn(completion);
        when(completion.onError(any())).thenReturn(errors);
        when(errors.execute()).thenReturn(responseHandle);
        doThrow(new UnsupportedOperationException("Not supported yet"))
                .when(responseHandle).cancel();
        ReflectionTestUtils.setField(model, "client", client);
        AtomicReference<StreamingRequestHandle> published = new AtomicReference<>();
        StreamingChatResponseHandler handler = handlerWithHandle(published);

        model.doChat(request(), handler);
        assertDoesNotThrow(() -> published.get().cancel());

        verify(errors).execute();
        verify(responseHandle).cancel();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unexpectedUnsupportedCancellationStillEscapes() {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey("test-key").modelName("test-model").build();
        OpenAiClient client = mock(OpenAiClient.class);
        SyncOrAsyncOrStreaming<dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse>
                request = mock(SyncOrAsyncOrStreaming.class);
        StreamingResponseHandling partial = mock(StreamingResponseHandling.class);
        StreamingCompletionHandling completion = mock(StreamingCompletionHandling.class);
        ErrorHandling errors = mock(ErrorHandling.class);
        ResponseHandle responseHandle = mock(ResponseHandle.class);
        when(client.chatCompletion(any())).thenReturn(request);
        when(request.onPartialResponse(any())).thenReturn(partial);
        when(partial.onComplete(any())).thenReturn(completion);
        when(completion.onError(any())).thenReturn(errors);
        when(errors.execute()).thenReturn(responseHandle);
        UnsupportedOperationException failure =
                new UnsupportedOperationException("取消链路内部状态异常");
        doThrow(failure).when(responseHandle).cancel();
        ReflectionTestUtils.setField(model, "client", client);
        AtomicReference<StreamingRequestHandle> published =
                new AtomicReference<>();

        model.doChat(request(), handlerWithHandle(published));

        assertSame(failure, assertThrows(
                UnsupportedOperationException.class,
                () -> published.get().cancel()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void synchronousExecuteFailureReportsErrorOnlyOnce() {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey("test-key").modelName("test-model").build();
        OpenAiClient client = mock(OpenAiClient.class);
        SyncOrAsyncOrStreaming<dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse>
                request = mock(SyncOrAsyncOrStreaming.class);
        StreamingResponseHandling partial = mock(StreamingResponseHandling.class);
        StreamingCompletionHandling completion = mock(StreamingCompletionHandling.class);
        ErrorHandling errors = mock(ErrorHandling.class);
        when(client.chatCompletion(any())).thenReturn(request);
        when(request.onPartialResponse(any())).thenReturn(partial);
        when(partial.onComplete(any())).thenReturn(completion);
        when(completion.onError(any())).thenReturn(errors);
        when(errors.execute()).thenThrow(new IllegalStateException("同步启动失败"));
        ReflectionTestUtils.setField(model, "client", client);
        AtomicInteger errorCalls = new AtomicInteger();
        StreamingChatResponseHandler handler = handlerWithError(errorCalls);

        model.doChat(request(), handler);

        assertEquals(1, errorCalls.get());
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .messages(UserMessage.from("test"))
                .parameters(OpenAiChatRequestParameters.builder()
                        .modelName("test-model").build())
                .build();
    }

    private static StreamingChatResponseHandler handlerWithHandle(
            AtomicReference<StreamingRequestHandle> published) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onRequestHandle(StreamingRequestHandle handle) {
                published.set(handle);
            }

            @Override public void onPartialResponse(String partialResponse) { }
            @Override public void onCompleteResponse(
                    dev.langchain4j.model.chat.response.ChatResponse response) { }
            @Override public void onError(Throwable error) { }
        };
    }

    private static StreamingChatResponseHandler handlerWithError(AtomicInteger errors) {
        return new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String partialResponse) { }
            @Override public void onCompleteResponse(
                    dev.langchain4j.model.chat.response.ChatResponse response) { }
            @Override public void onError(Throwable error) { errors.incrementAndGet(); }
        };
    }

    @Test
    void shouldDisableReasoningEffortForDeepSeekV4Flash() {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-v4-flash")
                .reasoningEffort("high")
                .build();

        OpenAiChatRequestParameters compatible =
                OpenAiStreamingChatModel.disableReasoningEffortForDeepSeekV4Flash(parameters);

        assertEquals("deepseek-v4-flash", compatible.modelName());
        assertNull(compatible.reasoningEffort());
    }

    @Test
    void shouldKeepReasoningEffortForNonDeepSeekV4FlashModel() {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-chat")
                .reasoningEffort("high")
                .build();

        OpenAiChatRequestParameters compatible =
                OpenAiStreamingChatModel.disableReasoningEffortForDeepSeekV4Flash(parameters);

        assertEquals("deepseek-chat", compatible.modelName());
        assertEquals("high", compatible.reasoningEffort());
    }

    @Test
    void shouldKeepParametersWhenReasoningEffortIsEmpty() {
        OpenAiChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-v4-flash")
                .build();

        OpenAiChatRequestParameters compatible =
                OpenAiStreamingChatModel.disableReasoningEffortForDeepSeekV4Flash(parameters);

        assertSame(parameters, compatible);
    }
}
