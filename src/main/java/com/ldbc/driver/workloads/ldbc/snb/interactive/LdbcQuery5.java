package com.ldbc.driver.workloads.ldbc.snb.interactive;

import com.ldbc.driver.Operation;
import com.ldbc.driver.SerializingMarshallingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LdbcQuery5 extends Operation<List<LdbcQuery5Result>> {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final int DEFAULT_LIMIT = 20;
    private final long personId;
    private final String personUri;
    private final Date minDate;
    private final int limit;

    public LdbcQuery5(long personId, String personUri, Date minDate, int limit) {
        super();
        this.personId = personId;
        this.personUri = personUri;
        this.minDate = minDate;
        this.limit = limit;
    }

    public long personId() {
        return personId;
    }

    public String personUri() {
        return personUri;
    }

    public Date minDate() {
        return minDate;
    }

    public int limit() {
        return limit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LdbcQuery5 that = (LdbcQuery5) o;

        if (limit != that.limit) return false;
        if (personId != that.personId) return false;
        if (minDate != null ? !minDate.equals(that.minDate) : that.minDate != null) return false;
        if (personUri != null ? !personUri.equals(that.personUri) : that.personUri != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) (personId ^ (personId >>> 32));
        result = 31 * result + (personUri != null ? personUri.hashCode() : 0);
        result = 31 * result + (minDate != null ? minDate.hashCode() : 0);
        result = 31 * result + limit;
        return result;
    }

    @Override
    public String toString() {
        return "LdbcQuery5{" +
                "personId=" + personId +
                ", personUri='" + personUri + '\'' +
                ", minDate=" + minDate +
                ", limit=" + limit +
                '}';
    }

    @Override
    public List<LdbcQuery5Result> marshalResult(String serializedResults) throws SerializingMarshallingException {
        List<List<Object>> resultsAsList;
        try {
            resultsAsList = objectMapper.readValue(serializedResults, new TypeReference<List<List<Object>>>() {
            });
        } catch (IOException e) {
            throw new SerializingMarshallingException(String.format("Error while parsing serialized results\n%s", serializedResults), e);
        }

        List<LdbcQuery5Result> results = new ArrayList<>();
        for (int i = 0; i < resultsAsList.size(); i++) {
            List<Object> resultAsList = resultsAsList.get(i);
            String forumTitle = (String) resultAsList.get(0);
            int postCount = ((Number) resultAsList.get(1)).intValue();

            results.add(new LdbcQuery5Result(
                    forumTitle,
                    postCount
            ));
        }

        return results;
    }

    @Override
    public String serializeResult(Object resultsObject) throws SerializingMarshallingException {
        List<LdbcQuery5Result> results = (List<LdbcQuery5Result>) resultsObject;
        List<List<Object>> resultsFields = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            LdbcQuery5Result result = results.get(i);
            List<Object> resultFields = new ArrayList<>();
            resultFields.add(result.forumTitle());
            resultFields.add(result.postCount());
            resultsFields.add(resultFields);
        }

        try {
            return objectMapper.writeValueAsString(resultsFields);
        } catch (IOException e) {
            throw new SerializingMarshallingException(String.format("Error while trying to serialize result\n%s", results.toString()), e);
        }
    }
}