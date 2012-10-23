// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.api.commands;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseListTaggedResourcesCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.response.ListResponse;
import com.cloud.api.response.SnapshotResponse;
import com.cloud.async.AsyncJob;
import com.cloud.storage.Snapshot;
import com.cloud.utils.Pair;


@Implementation(description="Lists all available snapshots for the account.", responseObject=SnapshotResponse.class)
public class ListSnapshotsCmd extends BaseListTaggedResourcesCmd {
	public static final Logger s_logger = Logger.getLogger(ListSnapshotsCmd.class.getName());

    private static final String s_name = "listsnapshotsresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @IdentityMapper(entityTableName="snapshots")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, description="lists snapshot by snapshot ID")
    private Long id;

    @Parameter(name=ApiConstants.INTERVAL_TYPE, type=CommandType.STRING, description="valid values are HOURLY, DAILY, WEEKLY, and MONTHLY.")
    private String intervalType;

    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, description="lists snapshot by snapshot name")
    private String snapshotName;

    @Parameter(name=ApiConstants.SNAPSHOT_TYPE, type=CommandType.STRING, description="valid values are MANUAL or RECURRING.")
    private String snapshotType;

    @IdentityMapper(entityTableName="volumes")
    @Parameter(name=ApiConstants.VOLUME_ID, type=CommandType.LONG, description="the ID of the disk volume")
    private Long volumeId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getIntervalType() {
        return intervalType;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public String getSnapshotType() {
        return snapshotType;
    }

    public Long getVolumeId() {
        return volumeId;
    }
    
    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }
    
    public AsyncJob.Type getInstanceType() {
    	return AsyncJob.Type.Snapshot;
    }

    @Override
    public void execute(){
        Pair<List<? extends Snapshot>, Integer> result = _snapshotService.listSnapshots(this);
        ListResponse<SnapshotResponse> response = new ListResponse<SnapshotResponse>();
        List<SnapshotResponse> snapshotResponses = new ArrayList<SnapshotResponse>();
        for (Snapshot snapshot : result.first()) {
            SnapshotResponse snapshotResponse = _responseGenerator.createSnapshotResponse(snapshot);
            snapshotResponse.setObjectName("snapshot");
            snapshotResponses.add(snapshotResponse);
        }
        response.setResponses(snapshotResponses, result.second());
        response.setResponseName(getCommandName());
        
        this.setResponseObject(response);
    }
}
