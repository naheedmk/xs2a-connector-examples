package de.adorsys.aspsp.xs2a.connector.spi.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.adorsys.aspsp.xs2a.util.TestConfiguration;
import feign.FeignException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {FeignExceptionReader.class, TestConfiguration.class})
public class FeignExceptionReaderTest {
    private static final String DEV_MESSAGE = "devMessage";

    @Autowired
    private FeignExceptionReader feignExceptionReader;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void getErrorMessageSuccess() throws JsonProcessingException {
        //Given
        String feignBodyString = buildFeignBodyMessage(DEV_MESSAGE);
        FeignException feignException = FeignException.errorStatus("", FeignExceptionHandler.error(HttpStatus.BAD_REQUEST, feignBodyString));
        //When
        String errorMessage = feignExceptionReader.getErrorMessage(feignException);
        //Then
        assertEquals(DEV_MESSAGE, errorMessage);
    }

    @Test
    public void getErrorMessageNoDevMessage() throws JsonProcessingException {
        //Given
        String feignBodyString = buildFeignBodyMessage("message", DEV_MESSAGE);
        FeignException feignException = FeignException.errorStatus("", FeignExceptionHandler.error(HttpStatus.BAD_REQUEST, feignBodyString));
        //When
        String errorMessage = feignExceptionReader.getErrorMessage(feignException);
        //Then
        assertNull(errorMessage);
    }

    @Test
    public void getErrorMessageNoContent() {
        //Given
        FeignException feignException = FeignException.errorStatus("", FeignExceptionHandler.error(HttpStatus.BAD_REQUEST));
        //When
        String errorMessage = feignExceptionReader.getErrorMessage(feignException);
        //Then
        assertNull(errorMessage);
    }

    private String buildFeignBodyMessage(String devMessage) throws JsonProcessingException {
        return buildFeignBodyMessage("devMessage", devMessage);
    }

    private String buildFeignBodyMessage(String key, String value) throws JsonProcessingException {
        Map<String, String> feignBody = new HashMap<>();
        feignBody.put(key, value);
        return objectMapper.writeValueAsString(feignBody);
    }
}
