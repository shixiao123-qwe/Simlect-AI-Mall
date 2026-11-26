package com.simlect.component;
import com.simlect.api.support.ProductFeignSupport;
import com.simlect.biz.RagQuestionService;
import com.simlect.entity.po.RagQuestion;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.Request;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class RagLightweightSafetyTest {
 @Test void failedFaqUpsertDoesNotDeletePreviousKnowledge() {
  var listener=new RabbitMQRagListenerComponent();var store=mock(VectorStore.class);var faq=mock(RagQuestionService.class);
  var row=new RagQuestion();row.setQuestion("question");row.setAnswer("answer");when(faq.getRagQuestionByQuestionId(1)).thenReturn(row);
  ReflectionTestUtils.setField(listener,"vectorStore",store);ReflectionTestUtils.setField(listener,"ragQuestionService",faq);
  doThrow(new IllegalStateException("embedding failed")).when(store).add(anyList());
  assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(listener,"save2vectorDB4FAQ","1"));
  verify(store,never()).delete(anyList());
 }
 @Test void deletedProductUsesBusinessIdNotDocumentPrefix() throws Exception {
  var listener=new RabbitMQRagListenerComponent();var es=mock(RestClient.class);
  ReflectionTestUtils.setField(listener,"productFeignSupport",mock(ProductFeignSupport.class));ReflectionTestUtils.setField(listener,"restClient",es);ReflectionTestUtils.setField(listener,"indexName","test-index");
  ReflectionTestUtils.invokeMethod(listener,"save2vectorDB4Product","p1");
  var captor=org.mockito.ArgumentCaptor.forClass(Request.class);verify(es).performRequest(captor.capture());
  String body=new String(captor.getValue().getEntity().getContent().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(body.contains("metadata.productId"));assertTrue(body.contains("\"p1\""));assertFalse(body.contains("productp1"));
 }
}
