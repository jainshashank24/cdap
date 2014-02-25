package com.continuuity.data2.dataset.lib.table.hbase;

import com.continuuity.api.common.Bytes;
import com.continuuity.data2.dataset.lib.hbase.AbstractHBaseDataSetManager;
import com.continuuity.data2.dataset.lib.table.TimeToLiveSupported;
import com.continuuity.data2.util.hbase.HBaseTableUtil;
import com.google.inject.Inject;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;

import java.io.IOException;
import java.util.Properties;

/**
 * Data set manager for hbase metrics tables. Implements TimeToLiveSupported as an indication of TTL.
 */
public class HBaseMetricsTableManager extends AbstractHBaseDataSetManager implements TimeToLiveSupported {

  private static final byte[] DATA_COLUMN_FAMILY = Bytes.toBytes("d");

  @Inject
  public HBaseMetricsTableManager(Configuration hConf, HBaseTableUtil tableUtil) throws IOException {
    super(new HBaseAdmin(hConf), tableUtil);
  }

  @Override
  protected String getHBaseTableName(String name) {
    return HBaseTableUtil.getHBaseTableName(name);
  }

  @Override
  public boolean exists(String name) throws Exception {
    return admin.tableExists(getHBaseTableName(name));
  }

  @Override
  public void create(String name) throws Exception {
    create(name, null);
  }

  @Override
  public void create(String name, Properties props) throws Exception {
    final String tableName = HBaseTableUtil.getHBaseTableName(name);

    final HColumnDescriptor columnDescriptor = new HColumnDescriptor(DATA_COLUMN_FAMILY);
    tableUtil.setBloomFilter(columnDescriptor, HBaseTableUtil.BloomType.ROW);
    columnDescriptor.setMaxVersions(1);

    if (props != null) {
      String hbaseTTL = props.getProperty(PROPERTY_TTL);
      if (hbaseTTL != null) {
        int ttl = Integer.parseInt(hbaseTTL);
        if (ttl > 0) {
          columnDescriptor.setTimeToLive(ttl);
        }
      }
    }

    final HTableDescriptor tableDescriptor = new HTableDescriptor(tableName);
    tableDescriptor.addFamily(columnDescriptor);
    tableUtil.createTableIfNotExists(admin, tableName, tableDescriptor);
  }

  @Override
  public void truncate(String name) throws Exception {
    byte[] tableName = Bytes.toBytes(getHBaseTableName(name));
    HTableDescriptor tableDescriptor = admin.getTableDescriptor(tableName);
    admin.disableTable(tableName);
    admin.deleteTable(tableName);
    admin.createTable(tableDescriptor);
  }

  @Override
  public void drop(String name) throws Exception {
    byte[] tableName = Bytes.toBytes(getHBaseTableName(name));
    admin.disableTable(tableName);
    admin.deleteTable(tableName);
  }

  @Override
  protected CoprocessorJar createCoprocessorJar() throws IOException {
    // No coprocessors for metrics table
    return CoprocessorJar.EMPTY;
  }
}
