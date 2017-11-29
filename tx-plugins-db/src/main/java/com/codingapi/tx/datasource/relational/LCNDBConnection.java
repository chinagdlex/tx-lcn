package com.codingapi.tx.datasource.relational;

import com.codingapi.tx.datasource.ICallClose;
import com.codingapi.tx.aop.bean.TxTransactionLocal;
import com.codingapi.tx.datasource.ILCNResource;
import com.codingapi.tx.datasource.service.DataSourceService;
import com.codingapi.tx.framework.task.TaskGroup;
import com.codingapi.tx.framework.task.TaskGroupManager;
import com.codingapi.tx.framework.task.TxTask;
import com.codingapi.tx.framework.thread.HookRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;


/**
 * create by lorne on 2017/7/29
 */

public class LCNDBConnection implements Connection,ILCNResource<Connection> {


    private Logger logger = LoggerFactory.getLogger(LCNDBConnection.class);

    private volatile int state = 1;

    private Connection connection;

    private DataSourceService dataSourceService;

    private ICallClose<LCNDBConnection> runnable;

    private int maxOutTime;

    private boolean hasGroup = false;

    private String groupId;

    private TxTask waitTask;


    public LCNDBConnection(Connection connection, DataSourceService dataSourceService, TxTransactionLocal transactionLocal, ICallClose<LCNDBConnection> runnable) {
        logger.info("init lcn connection ! ");
        this.connection = connection;
        this.runnable = runnable;
        this.dataSourceService = dataSourceService;
        groupId = transactionLocal.getGroupId();
        maxOutTime = transactionLocal.getMaxTimeOut();


        TaskGroup taskGroup = TaskGroupManager.getInstance().createTask(transactionLocal.getKid(),transactionLocal.getType());
        waitTask = taskGroup.getCurrent();
    }


    public void setHasIsGroup(boolean isGroup) {
        hasGroup = isGroup;
    }

    private boolean hasClose = false;


    @Override
    public void commit() throws SQLException {
        logger.info("commit label");

        state = 1;

        close();
        hasClose = true;
    }

    @Override
    public void rollback() throws SQLException {
        logger.info("rollback label");

        state = 0;

        close();
        hasClose = true;
    }

    protected void closeConnection() throws SQLException {
        runnable.close(this);
        connection.close();
        logger.info("lcnConnection closed groupId:"+ groupId);
    }

    @Override
    public void close() throws SQLException {

        if(connection==null||connection.isClosed()){
            return;
        }

        if(hasClose){
            hasClose = false;
            return;
        }

        if(connection.getAutoCommit()){

            closeConnection();

            //没有开启事务控制

            logger.info("now transaction over ! ");

            return;
        }else {

            logger.info("now transaction state is " + state + ", (1:commit,0:rollback) groupId:" + groupId);

            if (state == 0) {
                //再嵌套时，第一次成功后面出现回滚。
                if (waitTask != null && waitTask.isAwait() && !waitTask.isRemove()) {
                    //通知第一个连接回滚事务。
                    waitTask.setState(0);
                    waitTask.signalTask();
                } else {
                    connection.rollback();
                    closeConnection();
                }

                logger.info("rollback transaction ,groupId:" + groupId);
            }
            if (state == 1) {
                if (hasGroup) {
                    //加入队列的连接，仅操作连接对象，不处理事务
                    logger.info("connection hasGroup -> "+hasGroup);
                    return;
                }

                Runnable runnable = new HookRunnable() {
                    @Override
                    public void run0() {
                        try {
                            transaction();
                        } catch (Exception e) {
                            try {
                                connection.rollback();
                            } catch (SQLException e1) {
                                e1.printStackTrace();
                            }
                        } finally {
                            try {
                                closeConnection();
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                };
                Thread thread = new Thread(runnable);
                thread.start();
            }
        }
    }


    public void transaction() throws SQLException {
        if (waitTask == null) {
            connection.rollback();
            System.out.println(" waitTask is null");
            return;
        }


        //start 结束就是全部事务的结束表示,考虑start挂掉的情况
        Timer timer = new Timer();
        logger.info("maxOutTime:" + maxOutTime);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("auto execute ,groupId:" + getGroupId());
                dataSourceService.schedule(getGroupId(), waitTask);
            }
        }, maxOutTime);

        System.out.println("transaction is wait for TxManager notify, groupId : " + getGroupId());

        waitTask.awaitTask();

        timer.cancel();

        int rs = waitTask.getState();

        System.out.println("lcn transaction over, res -> groupId:"+getGroupId()+" and  state is "+rs+", about state (1:commit 0:rollback -1:network error -2:network time out)");

        if (rs == 1) {
            connection.commit();
        } else {
            connection.rollback();
        }
        waitTask.remove();

    }

    public String getGroupId() {
        return groupId;
    }

    public TxTask getWaitTask() {
        return waitTask;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {

        if(!autoCommit) {
            logger.info("setAutoCommit - >" + autoCommit);
            connection.setAutoCommit(autoCommit);

            TxTransactionLocal txTransactionLocal = TxTransactionLocal.current();
            txTransactionLocal.setAutoCommit(autoCommit);
        }
    }




    /*****default*******/

    @Override
    public Statement createStatement() throws SQLException {
        return connection.createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return connection.prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return connection.prepareCall(sql);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return connection.nativeSQL(sql);
    }


    @Override
    public boolean getAutoCommit() throws SQLException {
        return connection.getAutoCommit();
    }


    @Override
    public boolean isClosed() throws SQLException {
        return connection.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return connection.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        connection.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        connection.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return connection.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        connection.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return connection.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return connection.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        connection.clearWarnings();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return connection.createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return connection.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return connection.prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return connection.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        connection.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        connection.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return connection.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return connection.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return connection.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        connection.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        connection.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return connection.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return connection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return connection.prepareStatement(sql, autoGeneratedKeys);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return connection.prepareStatement(sql, columnIndexes);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return connection.prepareStatement(sql, columnNames);
    }

    @Override
    public Clob createClob() throws SQLException {
        return connection.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return connection.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return connection.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return connection.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return connection.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        connection.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        connection.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return connection.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return connection.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return connection.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return connection.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        connection.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return connection.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        connection.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        connection.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return connection.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return connection.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return connection.isWrapperFor(iface);
    }
}
