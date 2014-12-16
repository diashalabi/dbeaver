/*
 * Copyright (C) 2013      Denis Forveille titou10.titou10@gmail.com
 * Copyright (C) 2010-2014 Serge Rieder serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.ext.db2.model.plan;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/**
 * DB2 EXPLAIN_STATEMENT table
 * 
 * @author Denis Forveille
 */
public class DB2PlanStatement {

    private static final Log LOG = LogFactory.getLog(DB2PlanAnalyser.class);

    private static final String SEL_BASE_SELECT;

    static {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("SELECT *");
        sb.append("  FROM %s.%s");
        sb.append(" WHERE EXPLAIN_REQUESTER = ?"); // 1
        sb.append("   AND EXPLAIN_TIME = ?");// 2
        sb.append("   AND SOURCE_NAME = ?");// 3
        sb.append("   AND SOURCE_SCHEMA = ?");// 4
        sb.append("   AND SOURCE_VERSION = ?");// 5
        sb.append("   AND EXPLAIN_LEVEL = ?");// 6
        sb.append("   AND STMTNO = ?");// 7
        sb.append("   AND SECTNO = ?");// 8
        sb.append(" ORDER BY %s");
        sb.append(" WITH UR");
        SEL_BASE_SELECT = sb.toString();
    }

    private Map<String, DB2PlanOperator> mapOperators;
    private Map<String, DB2PlanObject> mapDataObjects;
    private List<DB2PlanStream> listStreams;

    private DB2PlanInstance planInstance;
    private String planTableSchema;

    private String explainRequester;
    private Timestamp explainTime;
    private String sourceName;
    private String sourceSchema;
    private String sourceVersion;
    private String explainLevel;
    private Integer stmtNo;
    private Integer sectNo;
    private Double totalCost;
    private String statementText;
    private Integer queryDegree;

    // ------------
    // Constructors
    // ------------
    public DB2PlanStatement(JDBCSession session, JDBCResultSet dbResult, String planTableSchema) throws SQLException
    {

        this.planTableSchema = planTableSchema;

        this.explainRequester = JDBCUtils.safeGetStringTrimmed(dbResult, "EXPLAIN_REQUESTER");
        this.explainTime = JDBCUtils.safeGetTimestamp(dbResult, "EXPLAIN_TIME");
        this.sourceName = JDBCUtils.safeGetStringTrimmed(dbResult, "SOURCE_NAME");
        this.sourceSchema = JDBCUtils.safeGetStringTrimmed(dbResult, "SOURCE_SCHEMA");
        this.sourceVersion = JDBCUtils.safeGetStringTrimmed(dbResult, "SOURCE_VERSION");
        this.explainLevel = JDBCUtils.safeGetStringTrimmed(dbResult, "EXPLAIN_LEVEL");
        this.stmtNo = JDBCUtils.safeGetInteger(dbResult, "STMTNO");
        this.sectNo = JDBCUtils.safeGetInteger(dbResult, "SECTNO");
        this.totalCost = JDBCUtils.safeGetDouble(dbResult, "TOTAL_COST");
        this.queryDegree = JDBCUtils.safeGetInteger(dbResult, "QUERY_DEGREE");

        this.statementText = JDBCUtils.safeGetString(dbResult, "STATEMENT_TEXT");

        loadChildren(session);
    }

    // ----------------
    // Business Methods
    // ----------------
    public Collection<DB2PlanNode> buildNodes()
    {
        // Based on streams, establish relationships between nodes
        // DF: VEry Important!: The Stream MUST be order by STREAM_ID DESC for the viewer to display things right (from the list
        // order)

        DB2PlanNode sourceNode;
        DB2PlanNode targetNode;
        for (DB2PlanStream planStream : listStreams) {

            // LOG.debug(planStream.getStreamId() + " src=" + planStream.getSourceName() + " tgt=" + planStream.getTargetName());

            // DF: "Data Objects" may be "target" of "Explain" Streams and have multiple parents..
            // DBeaver Explain Plan Viewer shows nodes in parent-child hierarchy so a node can not have multiple "parents"
            // It seems reasonable to reverse the stream after cloning the Object because the same Data Object has multiple
            // parents

            // Get Source Node
            if (planStream.getSourceType().equals(DB2PlanNodeType.D)) {
                sourceNode = mapDataObjects.get(planStream.getSourceName());
                sourceNode = new DB2PlanObject((DB2PlanObject) sourceNode);
            } else {
                sourceNode = mapOperators.get(planStream.getSourceName());
            }

            // Get Target Node
            if (planStream.getTargetType().equals(DB2PlanNodeType.D)) {
                targetNode = mapDataObjects.get(planStream.getTargetName());
                targetNode = new DB2PlanObject((DB2PlanObject) targetNode);

                // Inverse target <-> source
                sourceNode.getNested().add(targetNode);
                targetNode.setParent(sourceNode);
            } else {
                targetNode = mapOperators.get(planStream.getTargetName());

                targetNode.getNested().add(sourceNode);
                targetNode.setEstimatedCardinality(planStream.getStreamCount());
                sourceNode.setParent(targetNode);
            }

        }

        // Return "root" node, ie the operator with id=1
        DB2PlanNode rootNode = mapOperators.get("1");
        return rootNode == null ?
            Collections.<DB2PlanNode>emptyList() :
            Collections.singletonList(rootNode);
    }

    // -------------
    // Load children
    // -------------
    private void loadChildren(JDBCSession session) throws SQLException
    {

        mapDataObjects = new HashMap<String, DB2PlanObject>(32);
        JDBCPreparedStatement sqlStmt = session.prepareStatement(String.format(SEL_BASE_SELECT, planTableSchema, "EXPLAIN_OBJECT",
            "OBJECT_SCHEMA,OBJECT_NAME"));
        try {
            setQueryParameters(sqlStmt);
            JDBCResultSet res = sqlStmt.executeQuery();
            try {
                DB2PlanObject db2PlanObject;
                while (res.next()) {
                    db2PlanObject = new DB2PlanObject(res);
                    mapDataObjects.put(db2PlanObject.getNodeName(), db2PlanObject);
                }
            } finally {
                res.close();
            }
        } finally {
            sqlStmt.close();
        }

        mapOperators = new HashMap<String, DB2PlanOperator>(64);
        sqlStmt = session.prepareStatement(String.format(SEL_BASE_SELECT, planTableSchema, "EXPLAIN_OPERATOR", "OPERATOR_ID"));
        try {
            setQueryParameters(sqlStmt);
            JDBCResultSet res = sqlStmt.executeQuery();
            try {
                DB2PlanOperator db2PlanOperator;
                while (res.next()) {
                    db2PlanOperator = new DB2PlanOperator(session, res, this, planTableSchema);
                    mapOperators.put(db2PlanOperator.getNodeName(), db2PlanOperator);
                }
            } finally {
                res.close();
            }
        } finally {
            sqlStmt.close();
        }

        listStreams = new ArrayList<DB2PlanStream>();
        sqlStmt = session.prepareStatement(String.format(SEL_BASE_SELECT, planTableSchema, "EXPLAIN_STREAM", "STREAM_ID DESC"));
        try {
            setQueryParameters(sqlStmt);
            JDBCResultSet res = sqlStmt.executeQuery();
            try {
                while (res.next()) {
                    listStreams.add(new DB2PlanStream(res, this));
                }
            } finally {
                res.close();
            }
        } finally {
            sqlStmt.close();
        }
    }

    private void setQueryParameters(JDBCPreparedStatement sqlStmt) throws SQLException
    {
        sqlStmt.setString(1, explainRequester);
        sqlStmt.setTimestamp(2, explainTime);
        sqlStmt.setString(3, sourceName);
        sqlStmt.setString(4, sourceSchema);
        sqlStmt.setString(5, sourceVersion);
        sqlStmt.setString(6, explainLevel);
        sqlStmt.setInt(7, stmtNo);
        sqlStmt.setInt(8, sectNo);
    }

    // ----------------
    // Standard Getters
    // ----------------

    public DB2PlanInstance getPlanInstance()
    {
        return planInstance;
    }

    public String getExplainLevel()
    {
        return explainLevel;
    }

    public Integer getStmtNo()
    {
        return stmtNo;
    }

    public Integer getSectNo()
    {
        return sectNo;
    }

    public Double getTotalCost()
    {
        return totalCost;
    }

    public String getStatementText()
    {
        return statementText;
    }

    public Integer getQueryDegree()
    {
        return queryDegree;
    }

    public List<DB2PlanStream> getListStreams()
    {
        return listStreams;
    }

    public String getPlanTableSchema()
    {
        return planTableSchema;
    }

    public String getExplainRequester()
    {
        return explainRequester;
    }

    public Timestamp getExplainTime()
    {
        return explainTime;
    }

    public String getSourceName()
    {
        return sourceName;
    }

    public String getSourceSchema()
    {
        return sourceSchema;
    }

    public String getSourceVersion()
    {
        return sourceVersion;
    }

}
