/*
 * Copyright (C) 2021-2022 TOKYO SYSTEM HOUSE Co., Ltd.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 3.0,
 * or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; see the file COPYING.LIB.  If
 * not, write to the Free Software Foundation, 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */
package jp.osscons.opensourcecobol.libcobj.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jp.osscons.opensourcecobol.libcobj.common.CobolModule;
import jp.osscons.opensourcecobol.libcobj.common.CobolUtil;
import jp.osscons.opensourcecobol.libcobj.data.AbstractCobolField;
import jp.osscons.opensourcecobol.libcobj.data.CobolDataStorage;
import jp.osscons.opensourcecobol.libcobj.data.CobolFieldFactory;
import jp.osscons.opensourcecobol.libcobj.exceptions.CobolStopRunException;

public class CobolFileSort {
  protected static final int COBSORTEND = 1;
  protected static final int COBSORTABORT = 2;
  protected static final int COBSORTFILEERR = 3;
  protected static final int COBSORTNOTOPEN = 4;

  protected static final int COB_ASCENDING = 0;
  protected static final int COB_DESCENDING = 1;

  private static String cob_process_id = "";
  private static int cob_iteration = 0;

  protected static int cob_sort_memory = 128 * 1024 * 1024;

  // Javaの標準ライブラリでソートするならtrue
  private static boolean SORT_STD_LIB = true;
  private static List<CobolDataStorage> dataList;

  /**
   * libcob/fileio.cのsort_cmpsの実装
   *
   * @param s1
   * @param s2
   * @param size
   * @param col
   * @return
   */
  private static int sortCmps(
      CobolDataStorage s1, CobolDataStorage s2, int size, CobolDataStorage col) {
    if (col != null) {
      for (int i = 0; i < size; ++i) {
        int ret = col.getByte(s1.getByte(i)) - col.getByte(s2.getByte(i));
        if (ret != 0) {
          return ret;
        }
      }
    } else {
      for (int i = 0; i < size; ++i) {
        int ret = s1.getByte(i) - s2.getByte(i);
        if (ret != 0) {
          return ret;
        }
      }
    }
    return 0;
  }

  private static void uniqueCopy(CobolDataStorage s1, CobolDataStorage s2, int size) {
    for (int i = 0; i < size; ++i) {
      s1.setByte(s2.getByte(i));
    }
  }

  /**
   * libcob/fileio.cのcob_file_sort_compareの実装
   *
   * @param k1
   * @param k2
   * @param pointer
   * @return
   */
  private static int sortCompare(CobolItem k1, CobolItem k2, CobolFile pointer) {
    CobolFile f = pointer;
    long u1;
    long u2;
    int cmp;

    for (int i = 0; i < f.nkeys; i++) {
      AbstractCobolField src = f.keys[i].getField();

      CobolDataStorage d1 = k1.getItem().copy();
      d1.addIndex(f.keys[i].getOffset());

      CobolDataStorage d2 = k2.getItem().copy();
      d2.addIndex(f.keys[i].getOffset());

      AbstractCobolField f1 =
          CobolFieldFactory.makeCobolField(src.getSize(), d1, src.getAttribute());
      AbstractCobolField f2 =
          CobolFieldFactory.makeCobolField(src.getSize(), d2, src.getAttribute());

      if (f1.getAttribute().isTypeNumeric()) {
        cmp = f1.numericCompareTo(f2);
      } else {
        cmp = sortCmps(f1.getDataStorage(), f2.getDataStorage(), f1.getSize(), f.sort_collating);
      }
      if (cmp != 0) {
        return (f.keys[i].getFlag() == COB_ASCENDING) ? cmp : -cmp;
      }
    }
    byte[] bu1 = new byte[8];
    byte[] bu2 = new byte[8];
    uniqueCopy(new CobolDataStorage(bu1), k1.getUnique(), 8);
    uniqueCopy(new CobolDataStorage(bu2), k2.getUnique(), 8);
    u1 = ByteBuffer.wrap(bu1).getLong();
    u2 = ByteBuffer.wrap(bu2).getLong();
    return u1 < u2 ? -1 : 1;
  }

  /**
   * libcob/fileio.cのcob_free_listの実装
   *
   * @param q
   */
  private static void cob_free_list(CobolItem q) {
    // nothing to do
  }

  /**
   * libcob/fileio.cのcob_new_itemの実装
   *
   * @param hp
   * @return
   */
  private static CobolItem newItem(CobolSort hp) {
    CobolItem q;
    if (hp.getEmpty() != null) {
      q = hp.getEmpty();
      hp.setEmpty(q.getNext());
    } else {
      q = new CobolItem();
    }
    return q;
  }

  /**
   * libcob/fileio.cのcob_tmpfileの実装
   *
   * @return
   */
  private static FileIO tmpfile() {
    String s;
    FileIO fp = new FileIO();
    if ((s = CobolUtil.getEnv("TMPDIR")) == null
        && (s = CobolUtil.getEnv("TMP")) == null
        && (s = CobolUtil.getEnv("TEMP")) == null) {
      s = "/tmp";
    }
    if (cob_process_id.equals("")) {
      cob_process_id =
          java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }
    String filename = String.format("%s/cobsort_%s_%d", s, cob_process_id, cob_iteration);
    cob_iteration++;
    try {
      Files.delete(Paths.get(filename));
    } catch (IOException e1) {
    }
    FileChannel fc = null;
    try {
      fc =
          FileChannel.open(
              Paths.get(filename),
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      return null;
    }
    fp.setChannel(fc, null);
    return fp;
  }

  /**
   * libcob/fileio.cのcob_get_temp_fileの実装
   *
   * @param hp
   * @param n
   * @return
   */
  private static boolean getTempFile(CobolSort hp, int n) {
    if (hp.getFile()[n].getFp() == null) {
      hp.getFile()[n].setFp(tmpfile());
      if (hp.getFile()[n].getFp() == null) {
        // TODO 暫定実装
        System.out.println("SORT is unable to auire temporary file");
        System.exit(0);
      }
    } else {
      hp.getFile()[n].getFp().rewind();
    }
    hp.getFile()[n].setCount(0);
    return hp.getFile()[n].getFp() == null;
  }

  /**
   * libcob/fileio.cのcob_sort_queuesの実装
   *
   * @param hp
   * @return
   */
  private static int sortQueues(CobolSort hp) {
    CobolItem q;
    int source = 0;
    int destination;
    int move;
    int n;
    int[] end_of_block = new int[2];

    while (hp.getQueue()[source + 1].getCount() != 0) {
      destination = source ^ 2;
      hp.getQueue()[destination].setCount(0);
      hp.getQueue()[destination + 1].setCount(0);
      hp.getQueue()[destination].setFirst(null);
      hp.getQueue()[destination + 1].setFirst(null);
      for (; ; ) {
        end_of_block[0] = hp.getQueue()[source].getCount() == 0 ? 1 : 0;
        end_of_block[1] = hp.getQueue()[source + 1].getCount() == 0 ? 1 : 0;
        if (end_of_block[0] != 0 && end_of_block[1] != 0) {
          break;
        }
        while (end_of_block[0] == 0 || end_of_block[1] == 0) {
          if (end_of_block[0] != 0) {
            move = 1;
          } else if (end_of_block[1] != 0) {
            move = 0;
          } else {
            n =
                sortCompare(
                    hp.getQueue()[source].getFirst(),
                    hp.getQueue()[source + 1].getFirst(),
                    hp.getPointer());
            move = n < 0 ? 0 : 1;
          }
          q = hp.getQueue()[source + move].getFirst();
          if (q.getEndOfBlock() != 0) {
            end_of_block[move] = 1;
          }
          q = hp.getQueue()[source + move].getFirst();
          if (q.getEndOfBlock() != 0) {
            end_of_block[move] = 1;
          }
          hp.getQueue()[source + move].setFirst(q.getNext());
          if (hp.getQueue()[destination].getFirst() == null) {
            hp.getQueue()[destination].setFirst(q);
          } else {
            hp.getQueue()[destination].getLast().setNext(q);
          }
          hp.getQueue()[destination].setLast(q);
          hp.getQueue()[source + move].setCount(hp.getQueue()[source + move].getCount() - 1);
          hp.getQueue()[destination].setCount(hp.getQueue()[destination].getCount() + 1);
          q.setNext(null);
          q.setEndOfBlock(0);
        }
        hp.getQueue()[destination].getLast().setEndOfBlock(1);
        destination ^= 1;
      }
      source = destination & 2;
    }
    return source;
  }

  /**
   * libcob/fileio.cのcob_read_itemの実装
   *
   * @param hp
   * @param n
   * @return
   */
  private static int readItem(CobolSort hp, int n) {
    FileIO fp = hp.getFile()[n].getFp();
    if (fp.getc() != 0) {
      hp.getQueue()[n].getFirst().setEndOfBlock(1);
    } else {
      hp.getQueue()[n].getFirst().setEndOfBlock(0);
      try {
        if (fp.read(hp.getQueue()[n].getFirst().getUnique(), 8) != 8) {
          return 1;
        }
        if (fp.read(hp.getQueue()[n].getFirst().getItem(), hp.getSize()) != hp.getSize()) {
          return 1;
        }
      } catch (IOException e) {
        return 1;
      }
    }
    return 0;
  }

  /**
   * writeBlock内で使う補助メソッド
   *
   * @param fp
   * @param q
   * @param hp
   * @return 書き込み失敗時true,それ以外はfalse
   */
  private static boolean writeItem(FileIO fp, CobolItem q, CobolSort hp) {
    byte[] blockByteData = new byte[1];
    blockByteData[0] = q.getBlockByte();
    if (fp.write(blockByteData, 1, 1) != 1) {
      return true;
    }
    if (fp.write(q.getUnique(), 8, 1) != 1) {
      return true;
    }
    if (fp.write(q.getItem(), hp.getSize(), 1) != 1) {
      return true;
    }
    return false;
  }

  /**
   * libcob/fileio.cのcob_write_blockの実装
   *
   * @param hp
   * @param n
   * @return
   */
  private static int writeBlock(CobolSort hp, int n) {
    FileIO fp = hp.getFile()[hp.getDestinationFile()].getFp();
    for (; ; ) {
      CobolItem q = hp.getQueue()[n].getFirst();
      if (q == null) {
        break;
      }
      if (writeItem(fp, q, hp)) {
        return 1;
      }
      hp.getQueue()[n].setFirst(q.getNext());
      q.setNext(hp.getEmpty());
      hp.setEmpty(q);
    }
    hp.getQueue()[n].setCount(0);
    hp.getFile()[hp.getDestinationFile()].setCount(
        hp.getFile()[hp.getDestinationFile()].getCount() + 1);
    if (fp.putc((byte) 1) != 1) {
      return 1;
    }
    return 0;
  }

  /**
   * libcob/fileio.cのcob_copy_checkの実装
   *
   * @param from
   */
  private static void copyCheck(CobolFile to, CobolFile from) {
    CobolDataStorage toptr = to.record.getDataStorage();
    CobolDataStorage fromptr = from.record.getDataStorage();
    int tosize = to.record.getSize();
    int fromsize = from.record.getSize();
    if (tosize > fromsize) {
      for (int i = 0; i < fromsize; ++i) {
        toptr.setByte(i, fromptr.getByte(i));
      }
      for (int i = 0; i < tosize - fromsize; ++i) {
        toptr.setByte(fromsize + i, (byte) ' ');
      }
    } else {
      for (int i = 0; i < tosize; ++i) {
        toptr.setByte(i, fromptr.getByte(i));
      }
    }
  }

  /**
   * libcob/fileio.cのcob_file_sort_processの実装
   *
   * @param hp
   * @return
   */
  private static int sortProcess(CobolSort hp) {
    hp.setRetrieving(1);
    int n = sortQueues(hp);
    if (hp.getFilesUsed() == 0) {
      hp.setRetrievalQueue(n);
      return 0;
    }
    if (writeBlock(hp, n) != 0) {
      return COBSORTFILEERR;
    }
    for (int i = 0; i < 4; i++) {
      hp.getQueue()[i].setFirst(hp.getEmpty());
      hp.setEmpty(hp.getEmpty().getNext());
      hp.getQueue()[i].getFirst().setNext(null);
    }
    hp.getFile()[0].getFp().rewind();
    hp.getFile()[1].getFp().rewind();
    if (getTempFile(hp, 2)) {
      return COBSORTFILEERR;
    }
    if (getTempFile(hp, 3)) {
      return COBSORTFILEERR;
    }
    int source = 0;
    while (hp.getFile()[source].getCount() > 1) {
      int destination = source ^ 2;
      hp.getFile()[destination].setCount(0);
      hp.getFile()[destination + 1].setCount(0);
      while (hp.getFile()[source].getCount() > 0) {
        if (readItem(hp, source) != 0) {
          return COBSORTFILEERR;
        }
        if (hp.getFile()[source + 1].getCount() > 0) {
          if (readItem(hp, source + 1) != 0) {
            return COBSORTFILEERR;
          }
        } else {
          hp.getQueue()[source + 1].getFirst().setEndOfBlock(1);
        }
        while (hp.getQueue()[source].getFirst().getEndOfBlock() == 0
            || hp.getQueue()[source + 1].getFirst().getEndOfBlock() == 0) {
          int move;
          if (hp.getQueue()[source].getFirst().getEndOfBlock() != 0) {
            move = 1;
          } else if (hp.getQueue()[source + 1].getFirst().getEndOfBlock() != 0) {
            move = 0;
          } else {
            int res =
                sortCompare(
                    hp.getQueue()[source].getFirst(),
                    hp.getQueue()[source + 1].getFirst(),
                    hp.getPointer());
            move = res < 0 ? 0 : 1;
          }
          if (writeItem(
              hp.getFile()[destination].getFp(), hp.getQueue()[source + move].getFirst(), hp)) {
            return COBSORTFILEERR;
          }
          if (readItem(hp, source + move) != 0) {
            return COBSORTFILEERR;
          }
        }
        hp.getFile()[destination].setCount(hp.getFile()[destination].getCount() + 1);
        if (hp.getFile()[destination].getFp().putc((byte) 1) != 1) {
          return COBSORTFILEERR;
        }
        hp.getFile()[source].setCount(hp.getFile()[source].getCount() - 1);
        hp.getFile()[source + 1].setCount(hp.getFile()[source + 1].getCount() - 1);
        destination ^= 1;
      }
      source = destination & 2;
      for (int i = 0; i < 4; ++i) {
        hp.getFile()[i].getFp().rewind();
      }
    }
    hp.setRetrievalQueue(source);
    if (readItem(hp, source) != 0) {
      return COBSORTFILEERR;
    }
    if (readItem(hp, source + 1) != 0) {
      return COBSORTFILEERR;
    }
    return 0;
  }

  /**
   * libcob/fileio.cのcob_file_sort_submitの実装
   *
   * @param p
   * @return
   */
  private static int sortSubmit(CobolFile f, CobolDataStorage p) {

    if (SORT_STD_LIB) {
      dataList.add(new CobolDataStorage(p.getData()));
      return 0;
    } else {
      CobolSort hp = f.filex;
      if (hp == null) {
        return COBSORTNOTOPEN;
      }
      if (hp.getRetrieving() != 0) {
        return COBSORTABORT;
      }
      if (hp.getQueue()[0].getCount() + hp.getQueue()[1].getCount() >= hp.getMemory()) {
        if (hp.getFilesUsed() == 0) {
          if (getTempFile(hp, 0)) {
            return COBSORTFILEERR;
          }
          if (getTempFile(hp, 1)) {
            return COBSORTFILEERR;
          }
          hp.setFilesUsed(1);
          hp.setDestinationFile(0);
        }
        int n = sortQueues(hp);
        if (writeBlock(hp, n) != 0) {
          return COBSORTFILEERR;
        }
        hp.setDestinationFile(hp.getDestinationFile() ^ 1);
      }

      CobolItem q = newItem(hp);
      if (f.record_size != null) {
        q.setRecordSize(f.record_size.getInt());
      } else {
        q.setRecordSize(hp.getSize());
      }
      q.setEndOfBlock(1);

      {
        byte[] bytes = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.putInt(hp.getUnique());
        uniqueCopy(q.getUnique(), new CobolDataStorage(bytes), 8);
      }

      hp.setUnique(hp.getUnique() + 1);

      byte[] arr = p.getByteArray(0, hp.getSize());
      q.setItem(new CobolDataStorage(arr));

      MemoryStruct z;
      if (hp.getQueue()[0].getCount() <= hp.getQueue()[1].getCount()) {
        z = hp.getQueue()[0];
      } else {
        z = hp.getQueue()[1];
      }
      q.setNext(z.getFirst());
      z.setFirst(q);
      z.setCount(z.getCount() + 1);
      return 0;
    }
  }

  /**
   * libcob/fileio.cのcob_file_sort_retrieveの実装
   *
   * @param p
   * @return
   */
  private static int sortRetrieve(CobolFile f, CobolDataStorage p) {
    CobolSort hp = f.filex;
    if (hp == null) {
      return COBSORTNOTOPEN;
    }

    if (SORT_STD_LIB) {
      if (hp.getRetrieving() == 0) {
        memorySort(f);
        hp.setRetrieving(1);
      }
      if (dataList.isEmpty()) {
        return COBSORTEND;
      }
      CobolDataStorage storage = dataList.remove(0);
      p.setBytes(storage, hp.getSize());
      if (f.record_size != null) {
        f.record_size.setInt(hp.getSize());
      }
      return 0;
    }

    if (hp.getRetrieving() == 0) {
      int res = sortProcess(hp);
      if (res != 0) {
        return res;
      }
    }

    if (hp.getFilesUsed() != 0) {
      int source = hp.getRetrievalQueue();
      int move;
      if (hp.getQueue()[source].getFirst().getEndOfBlock() != 0) {
        if (hp.getQueue()[source].getFirst().getEndOfBlock() != 0) {
          return COBSORTEND;
        }
        move = 1;
      } else if (hp.getQueue()[source].getFirst().getEndOfBlock() != 0) {
        move = 0;
      } else {
        int res =
            sortCompare(
                hp.getQueue()[source].getFirst(),
                hp.getQueue()[source + 1].getFirst(),
                hp.getPointer());
        move = res < 0 ? 0 : 1;
      }
      for (int i = 0; i < hp.getSize(); ++i) {
        p.setByte(i, hp.getQueue()[source + move].getFirst().getItem().getByte(i));
      }
      if (readItem(hp, source + move) != 0) {
        return COBSORTFILEERR;
      }
    } else {
      MemoryStruct z = hp.getQueue()[hp.getRetrievalQueue()];
      if (z.getFirst() == null) {
        return COBSORTEND;
      }
      for (int i = 0; i < hp.getSize(); ++i) {
        p.setByte(i, z.getFirst().getItem().getByte(i));
      }
      if (f.record_size != null) {
        f.record_size.setInt(z.getFirst().getRecordSize());
      }
      CobolItem next = z.getFirst().getNext();
      z.getFirst().setNext(hp.getEmpty());
      hp.setEmpty(z.getFirst());
      z.setFirst(next);
    }
    return 0;
  }

  private static void memorySort(CobolFile f) {
    dataList.sort(
        new Comparator<CobolDataStorage>() {
          public int compare(CobolDataStorage data1, CobolDataStorage data2) {
            long u1;
            long u2;
            int cmp;

            for (int i = 0; i < f.nkeys; i++) {
              AbstractCobolField src = f.keys[i].getField();

              CobolDataStorage d1 = data1.copy();
              d1.addIndex(f.keys[i].getOffset());

              CobolDataStorage d2 = data2.copy();
              d2.addIndex(f.keys[i].getOffset());

              AbstractCobolField f1 =
                  CobolFieldFactory.makeCobolField(src.getSize(), d1, src.getAttribute());
              AbstractCobolField f2 =
                  CobolFieldFactory.makeCobolField(src.getSize(), d2, src.getAttribute());

              if (f1.getAttribute().isTypeNumeric()) {
                cmp = f1.numericCompareTo(f2);
              } else {
                cmp =
                    sortCmps(
                        f1.getDataStorage(), f2.getDataStorage(), f1.getSize(), f.sort_collating);
              }
              if (cmp != 0) {
                return (f.keys[i].getFlag() == COB_ASCENDING) ? cmp : -cmp;
              }
            }
            return 0;
          }
        });
  }

  /**
   * libcob/fileio.cのcob_file_sort_initの実装
   *
   * @param nkeys
   * @param collating_sequence
   * @param sort_return
   * @param fnstatus
   */
  public static void sortInit(
      CobolFile f,
      int nkeys,
      CobolDataStorage collating_sequence,
      CobolDataStorage sort_return,
      AbstractCobolField fnstatus) {
    int sizeOfSizeT = 8;
    CobolSort p = new CobolSort();
    p.setFnstatus(fnstatus);
    p.setSize(f.record_max);
    p.setrSize(f.record_max + sizeOfSizeT);
    p.setwSize(f.record_max + sizeOfSizeT + 1);
    p.setPointer(f);
    p.setSortReturn(sort_return);
    sort_return.set(0);
    // opensource COBOLでsizeof(struct cobitem) == 32だっため,32を使用した
    p.setMemory(cob_sort_memory / (p.getSize() + 32));
    dataList = new ArrayList<CobolDataStorage>();
    f.filex = p;
    f.keys = new CobolFileKey[nkeys];
    for (int i = 0; i < nkeys; ++i) {
      f.keys[i] = new CobolFileKey();
    }
    f.nkeys = 0;
    if (collating_sequence != null) {
      f.sort_collating = collating_sequence;
    } else {
      f.sort_collating = CobolModule.getCurrentModule().collating_sequence;
    }
    f.saveStatus(CobolFile.COB_STATUS_00_SUCCESS, fnstatus);
    return;
  }

  /**
   * libcob/fileio.cのcob_file_sort_initの実装
   *
   * @param nkeys
   * @param collating_sequence
   * @param sort_return
   * @param fnstatus
   */
  public static void sortInit(
      CobolFile f,
      int nkeys,
      int collating_sequence,
      CobolDataStorage sort_return,
      AbstractCobolField fnstatus) {
    sortInit(f, nkeys, null, sort_return, fnstatus);
  }

  /**
   * libcob/fileio.cのcob_file_sort_init_keyの実装
   *
   * @param flag
   * @param field
   * @param offset
   */
  public static void sortInitKey(CobolFile f, int flag, AbstractCobolField field, int offset) {
    f.keys[f.nkeys].setFlag(flag);
    f.keys[f.nkeys].setField(field);
    f.keys[f.nkeys].setOffset(offset);
    f.nkeys++;
  }

  /**
   * libcob/fileio.cのcob_file_sort_usingの実装
   *
   * @param data_file
   */
  public static void sortUsing(CobolFile sort_file, CobolFile data_file) {
    data_file.open(CobolFile.COB_OPEN_INPUT, 0, null);
    for (; ; ) {
      data_file.read(null, null, CobolFile.COB_READ_NEXT);
      if (data_file.file_status[0] != (byte) '0') {
        break;
      }
      copyCheck(sort_file, data_file);
      int ret = sortSubmit(sort_file, sort_file.record.getDataStorage());
      if (ret != 0) {
        break;
      }
    }
    data_file.close(CobolFile.COB_CLOSE_NORMAL, null);
  }

  /**
   * libcob/fileio.cのcob_file_sort_givingの実装
   *
   * @param varcnt
   * @param fbase
   * @throws CobolStopRunException
   */
  public static void sortGiving(CobolFile sort_file, int varcnt, CobolFile... fbase)
      throws CobolStopRunException {
    if (SORT_STD_LIB) {
      for (int i = 0; i < varcnt; i++) {
        fbase[i].open(CobolFile.COB_OPEN_OUTPUT, 0, null);
      }

      int cnt_rec = 0;
      memorySort(sort_file);
      for (CobolDataStorage d : dataList) {
        for (int i = 0; i < varcnt; i++) {
          int opt;
          if (fbase[i].special != 0 || fbase[i].organization == CobolFile.COB_ORG_LINE_SEQUENTIAL) {
            opt = CobolFile.COB_WRITE_BEFORE | CobolFile.COB_WRITE_LINES | 1;
          } else {
            opt = 0;
          }
          sort_file.record.getDataStorage().memcpy(d, sort_file.record.getSize());
          copyCheck(fbase[i], sort_file);
          fbase[i].write(fbase[i].record, opt, null);
        }
        cnt_rec++;
      }
      sort_file.file_status[0] = '1';
      sort_file.file_status[1] = '0';

      for (int i = 0; i < varcnt; i++) {
        fbase[i].close(CobolFile.COB_CLOSE_NORMAL, null);
      }
      CobolUtil.verboseOutput(String.format("END OF SORT/MERGE, RECORD %d.", cnt_rec));

    } else {

      for (int i = 0; i < varcnt; i++) {
        fbase[i].open(CobolFile.COB_OPEN_OUTPUT, 0, null);
      }
      int cnt_rec = 0;
      for (; ; ) {
        int ret = sortRetrieve(sort_file, sort_file.record.getDataStorage());
        if (ret != 0) {
          if (ret == COBSORTEND) {
            sort_file.file_status[0] = '1';
            sort_file.file_status[1] = '0';
          } else {
            CobolSort hp = sort_file.filex;
            hp.getSortReturn().set(16);
            sort_file.file_status[0] = '3';
            sort_file.file_status[1] = '0';
          }
          break;
        }
        for (int i = 0; i < varcnt; i++) {
          int opt;
          if (fbase[i].special != 0 || fbase[i].organization == CobolFile.COB_ORG_LINE_SEQUENTIAL) {
            opt = CobolFile.COB_WRITE_BEFORE | CobolFile.COB_WRITE_LINES | 1;
          } else {
            opt = 0;
          }
          copyCheck(fbase[i], sort_file);
          fbase[i].write(fbase[i].record, opt, null);
        }
        cnt_rec++;
      }
      for (int i = 0; i < varcnt; i++) {
        fbase[i].close(CobolFile.COB_CLOSE_NORMAL, null);
      }
      CobolUtil.verboseOutput(String.format("END OF SORT/MERGE, RECORD %d.", cnt_rec));
    }
  }

  /** libcob/fileio.cのcob_file_sort_closeの実装 */
  public static void sortClose(CobolFile f) {
    AbstractCobolField fnstatus = null;
    CobolSort hp = f.filex;

    if (hp != null) {
      fnstatus = hp.getFnstatus();
      cob_free_list(hp.getEmpty());
      for (int i = 0; i < 4; ++i) {
        cob_free_list(hp.getQueue()[i].getFirst());
        if (hp.getFile()[i].getFp() != null) {
          hp.getFile()[i].getFp().close();
        }
      }
    }
    f.filex = null;
    f.saveStatus(CobolFile.COB_STATUS_00_SUCCESS, fnstatus);
    return;
  }

  /** libcob/fileio.cのcob_file_releaseの実装 */
  public static void performRelease(CobolFile f) {
    AbstractCobolField fnstatus = null;
    CobolSort hp = f.filex;

    if (hp != null) {
      fnstatus = hp.getFnstatus();
    }
    int ret = CobolFileSort.sortSubmit(f, f.record.getDataStorage());
    switch (ret) {
      case 0:
        f.saveStatus(CobolFile.COB_STATUS_00_SUCCESS, fnstatus);
        return;
      default:
        if (hp != null) {
          hp.getSortReturn().set(16);
        }
        f.saveStatus(CobolFile.COB_STATUS_30_PERMANENT_ERROR, fnstatus);
        return;
    }
  }

  /** libcob/fileio.cのcob_file_returnの実装 */
  public static void performReturn(CobolFile f) {
    AbstractCobolField fnstatus = null;
    CobolSort hp = f.filex;
    if (hp != null) {
      fnstatus = hp.getFnstatus();
    }

    int ret = CobolFileSort.sortRetrieve(f, f.record.getDataStorage());
    switch (ret) {
      case 0:
        f.saveStatus(CobolFile.COB_STATUS_00_SUCCESS, fnstatus);
        return;
      case CobolFileSort.COBSORTEND:
        f.saveStatus(CobolFile.COB_STATUS_10_END_OF_FILE, fnstatus);
        return;
      default:
        if (hp != null) {
          hp.getSortReturn().set(16);
        }
        f.saveStatus(CobolFile.COB_STATUS_30_PERMANENT_ERROR, fnstatus);
        return;
    }
  }
}
