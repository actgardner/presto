/*
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
package com.facebook.presto.orc.stream;

import com.facebook.presto.orc.OrcCorruptionException;
import com.facebook.presto.orc.OrcDataSourceId;
import com.facebook.presto.orc.checkpoint.StreamCheckpoint;
import com.facebook.presto.orc.metadata.Stream;
import com.facebook.presto.orc.metadata.Stream.StreamKind;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public abstract class AbstractTestValueStream<T, C extends StreamCheckpoint, W extends ValueOutputStream<C>, R extends ValueInputStream<C>>
{
    static final int COMPRESSION_BLOCK_SIZE = 256 * 1024;
    static final OrcDataSourceId ORC_DATA_SOURCE_ID = new OrcDataSourceId("test");

    protected void testWriteValue(List<List<T>> groups)
            throws IOException
    {
        W outputStream = createValueOutputStream();
        for (int i = 0; i < 3; i++) {
            outputStream.reset();
            long retainedBytes = 0;
            for (List<T> group : groups) {
                outputStream.recordCheckpoint();
                group.forEach(value -> writeValue(outputStream, value));

                assertTrue(outputStream.getRetainedBytes() >= retainedBytes);
                retainedBytes = outputStream.getRetainedBytes();
            }
            outputStream.close();

            DynamicSliceOutput sliceOutput = new DynamicSliceOutput(1000);
            Optional<Stream> stream = outputStream.writeDataStreams(33, sliceOutput);
            assertTrue(stream.isPresent());
            assertEquals(stream.get().getStreamKind(), StreamKind.DATA);
            assertEquals(stream.get().getColumn(), 33);
            assertEquals(stream.get().getLength(), sliceOutput.size());

            List<C> checkpoints = outputStream.getCheckpoints();
            assertEquals(checkpoints.size(), groups.size());

            R valueStream = createValueStream(sliceOutput.slice());
            for (List<T> group : groups) {
                int index = 0;
                for (T expectedValue : group) {
                    index++;
                    T actualValue = readValue(valueStream);
                    if (!actualValue.equals(expectedValue)) {
                        assertEquals(actualValue, expectedValue, "index=" + index);
                    }
                }
            }
            for (int groupIndex = groups.size() - 1; groupIndex >= 0; groupIndex--) {
                valueStream.seekToCheckpoint(checkpoints.get(groupIndex));
                for (T expectedValue : groups.get(groupIndex)) {
                    T actualValue = readValue(valueStream);
                    if (!actualValue.equals(expectedValue)) {
                        assertEquals(actualValue, expectedValue);
                    }
                }
            }
        }
    }

    protected abstract W createValueOutputStream();

    protected abstract void writeValue(W outputStream, T value);

    protected abstract R createValueStream(Slice slice)
            throws OrcCorruptionException;

    protected abstract T readValue(R valueStream)
            throws IOException;
}
