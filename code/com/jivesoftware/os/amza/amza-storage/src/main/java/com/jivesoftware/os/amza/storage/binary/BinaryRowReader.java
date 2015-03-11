/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.amza.storage.binary;

import com.jivesoftware.os.amza.shared.RowReader;
import com.jivesoftware.os.amza.shared.RowReader.Stream;
import com.jivesoftware.os.amza.storage.filer.IFiler;
import com.jivesoftware.os.amza.storage.filer.UIO;
import java.io.IOException;

public class BinaryRowReader implements RowReader<byte[]> {

    private final IFiler filer; // TODO use mem-mapping and bb.dupliate to remove all the hard locks

    public BinaryRowReader(IFiler filer) {
        this.filer = filer;
    }

    @Override
    public void reverseScan(Stream<byte[]> stream) throws Exception {
        synchronized (filer.lock()) {
            long seekTo = filer.length() - 4; // last length int
            if (seekTo < 0) {
                return;
            }
            while (seekTo >= 0) {
                filer.seek(seekTo);
                int priorLength = UIO.readInt(filer, "priorLength");
                seekTo -= (priorLength + 4);
                if (seekTo < 0) {
                    return;
                }
                filer.seek(seekTo);

                int length = UIO.readInt(filer, "length");
                byte[] row = new byte[length];
                filer.read(row);
                if (!stream.row(seekTo, row)) {
                    return;
                }
                seekTo -= 4;
            }
        }
    }

    @Override
    public void scan(long offest, Stream<byte[]> stream) throws Exception {

        synchronized (filer.lock()) {
            if (filer.length() == 0) {
                return;
            }
            filer.seek(offest);
            while (filer.getFilePointer() < filer.length()) {
                long seekTo = filer.getFilePointer();
                int length = UIO.readInt(filer, "length");
                byte[] row = new byte[length];
                if (length > 0) {
                    filer.read(row);
                }
                if (!stream.row(seekTo, row)) {
                    return;
                }
                UIO.readInt(filer, "length");
            }
        }

    }

    @Override
    public byte[] read(byte[] rowPointer) throws IOException {
        synchronized (filer.lock()) {
            if (filer.length() == 0) {
                return null;
            }
            filer.seek(UIO.bytesLong(rowPointer));
            int length = UIO.readInt(filer, "length");
            byte[] row = new byte[length];
            if (length > 0) {
                filer.read(row);
            }
            return row;
        }
    }
}
