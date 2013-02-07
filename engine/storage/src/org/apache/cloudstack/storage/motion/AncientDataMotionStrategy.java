/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObjectType;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreRole;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreatePrivateTemplateFromSnapshotCommand;
import com.cloud.agent.api.CreatePrivateTemplateFromVolumeCommand;
import com.cloud.agent.api.CreateVolumeFromSnapshotAnswer;
import com.cloud.agent.api.CreateVolumeFromSnapshotCommand;
import com.cloud.agent.api.UpgradeSnapshotCommand;
import com.cloud.agent.api.storage.CopyVolumeAnswer;
import com.cloud.agent.api.storage.CopyVolumeCommand;
import com.cloud.agent.api.storage.CreateAnswer;
import com.cloud.agent.api.storage.CreateCommand;
import com.cloud.agent.api.storage.CreatePrivateTemplateAnswer;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateHostVO;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeHostVO;
import com.cloud.storage.VolumeManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateHostDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeHostDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.template.TemplateManager;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;

@Component
public class AncientDataMotionStrategy implements DataMotionStrategy {
    private static final Logger s_logger = Logger
            .getLogger(AncientDataMotionStrategy.class);
    @Inject
    TemplateManager templateMgr;
    @Inject
    VolumeHostDao volumeHostDao;
    @Inject
    HostDao hostDao;
    @Inject
    ConfigurationDao configDao;
    @Inject
    StorageManager storagMgr;
    @Inject
    VolumeDao volDao;
    @Inject
    VMTemplateDao templateDao;
    @Inject
    SnapshotManager snapshotMgr;
    @Inject
    SnapshotDao snapshotDao;
    @Inject
    PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    VMTemplateHostDao templateHostDao;
    @Inject DiskOfferingDao diskOfferingDao;
    @Inject VMTemplatePoolDao templatePoolDao;
    @Inject
    VolumeManager volumeMgr;

    @Override
    public boolean canHandle(DataObject srcData, DataObject destData) {
        // TODO Auto-generated method stub
        return true;
    }

    @DB
    protected String copyVolumeFromImage(DataObject srcData, DataObject destData) {
        String value = configDao.getValue(Config.RecreateSystemVmEnabled.key());
        int _copyvolumewait = NumbersUtil.parseInt(value,
                Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));

        VolumeHostVO volumeHostVO = volumeHostDao.findByVolumeId(srcData
                .getId());
        HostVO secStorage = hostDao.findById(volumeHostVO.getHostId());
        String secondaryStorageURL = secStorage.getStorageUrl();
        String[] volumePath = volumeHostVO.getInstallPath().split("/");
        String volumeUUID = volumePath[volumePath.length - 1].split("\\.")[0];
        StoragePool destPool = (StoragePool) destData.getDataStore();
        CopyVolumeCommand cvCmd = new CopyVolumeCommand(srcData.getId(),
                volumeUUID, destPool, secondaryStorageURL, false,
                _copyvolumewait);
        CopyVolumeAnswer cvAnswer = null;
        String errMsg = null;
        try {
            cvAnswer = (CopyVolumeAnswer) this.storagMgr.sendToPool(destPool,
                    cvCmd);
        } catch (StorageUnavailableException e1) {
            s_logger.debug("Failed to copy volume " + srcData.getId() + " to "
                    + destData.getId(), e1);
            errMsg = e1.toString();
        }

        if (cvAnswer == null || !cvAnswer.getResult()) {
            errMsg = cvAnswer.getDetails();
        }

        VolumeVO vol = this.volDao.findById(destData.getId());
        Transaction txn = Transaction.currentTxn();
        txn.start();
        vol.setPath(cvAnswer.getVolumePath());
        vol.setFolder(destPool.getPath());
        vol.setPodId(destPool.getPodId());
        vol.setPoolId(destPool.getId());
        vol.setPodId(destPool.getPodId());

        this.volDao.update(vol.getId(), vol);
        volumeHostDao.remove(volumeHostVO.getId());
        txn.commit();
        return errMsg;
    }

    private void copyTemplate(DataObject srcData, DataObject destData) {
        VMTemplateVO template = this.templateDao.findById(srcData.getId());
        templateMgr.prepareTemplateForCreate(template,
                (StoragePool) destData.getDataStore());
    }

    protected String copyFromSnapshot(DataObject snapObj, DataObject volObj) {
        SnapshotVO snapshot = this.snapshotDao.findById(snapObj.getId());
        StoragePool pool = (StoragePool) volObj.getDataStore();
        String vdiUUID = null;
        Long snapshotId = snapshot.getId();
        Long volumeId = snapshot.getVolumeId();
        Long dcId = snapshot.getDataCenterId();
        String secondaryStoragePoolUrl = this.snapshotMgr
                .getSecondaryStorageURL(snapshot);
        long accountId = snapshot.getAccountId();

        String backedUpSnapshotUuid = snapshot.getBackupSnapshotId();
        snapshot = snapshotDao.findById(snapshotId);
        if (snapshot.getVersion().trim().equals("2.1")) {
            VolumeVO volume = this.volDao.findByIdIncludingRemoved(volumeId);
            if (volume == null) {
                throw new CloudRuntimeException("failed to upgrade snapshot "
                        + snapshotId + " due to unable to find orignal volume:"
                        + volumeId + ", try it later ");
            }
            if (volume.getTemplateId() == null) {
                snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
            } else {
                VMTemplateVO template = templateDao
                        .findByIdIncludingRemoved(volume.getTemplateId());
                if (template == null) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot "
                                    + snapshotId
                                    + " due to unalbe to find orignal template :"
                                    + volume.getTemplateId()
                                    + ", try it later ");
                }
                Long templateId = template.getId();
                Long tmpltAccountId = template.getAccountId();
                if (!snapshotDao.lockInLockTable(snapshotId.toString(), 10)) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot "
                                    + snapshotId
                                    + " due to this snapshot is being used, try it later ");
                }
                UpgradeSnapshotCommand cmd = new UpgradeSnapshotCommand(null,
                        secondaryStoragePoolUrl, dcId, accountId, volumeId,
                        templateId, tmpltAccountId, null,
                        snapshot.getBackupSnapshotId(), snapshot.getName(),
                        "2.1");
                Answer answer = null;
                try {
                    answer = this.storagMgr.sendToPool(pool, cmd);
                } catch (StorageUnavailableException e) {
                } finally {
                    snapshotDao.unlockFromLockTable(snapshotId.toString());
                }
                if ((answer != null) && answer.getResult()) {
                    snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
                } else {
                    return "Unable to upgrade snapshot from 2.1 to 2.2 for "
                            + snapshot.getId();
                }
            }
        }
        String basicErrMsg = "Failed to create volume from "
                + snapshot.getName() + " on pool " + pool;

        try {
            if (snapshot.getSwiftId() != null && snapshot.getSwiftId() != 0) {
                snapshotMgr.downloadSnapshotsFromSwift(snapshot);
            } else if (snapshot.getS3Id() != null && snapshot.getS3Id() != 0) {
                snapshotMgr.downloadSnapshotsFromS3(snapshot);
            }
            String value = configDao
                    .getValue(Config.CreateVolumeFromSnapshotWait.toString());
            int _createVolumeFromSnapshotWait = NumbersUtil.parseInt(value,
                    Integer.parseInt(Config.CreateVolumeFromSnapshotWait
                            .getDefaultValue()));
            CreateVolumeFromSnapshotCommand createVolumeFromSnapshotCommand = new CreateVolumeFromSnapshotCommand(
                    pool, secondaryStoragePoolUrl, dcId, accountId, volumeId,
                    backedUpSnapshotUuid, snapshot.getName(),
                    _createVolumeFromSnapshotWait);
            CreateVolumeFromSnapshotAnswer answer;
            if (!snapshotDao.lockInLockTable(snapshotId.toString(), 10)) {
                throw new CloudRuntimeException("failed to create volume from "
                        + snapshotId
                        + " due to this snapshot is being used, try it later ");
            }
            answer = (CreateVolumeFromSnapshotAnswer) this.storagMgr
                    .sendToPool(pool, createVolumeFromSnapshotCommand);
            if (answer != null && answer.getResult()) {
                vdiUUID = answer.getVdi();
                VolumeVO vol = this.volDao.findById(volObj.getId());
                vol.setPath(vdiUUID);
                this.volDao.update(vol.getId(), vol);
                return null;
            } else {
                s_logger.error(basicErrMsg + " due to "
                        + ((answer == null) ? "null" : answer.getDetails()));
                throw new CloudRuntimeException(basicErrMsg);
            }
        } catch (StorageUnavailableException e) {
            s_logger.error(basicErrMsg, e);
            throw new CloudRuntimeException(basicErrMsg);
        } finally {
            if (snapshot.getSwiftId() != null) {
                snapshotMgr.deleteSnapshotsDirForVolume(
                        secondaryStoragePoolUrl, dcId, accountId, volumeId);
            }
            snapshotDao.unlockFromLockTable(snapshotId.toString());
        }
    }
    
    protected String cloneVolume(DataObject template, DataObject volume) {
        VolumeInfo volInfo = (VolumeInfo)volume;
        DiskOfferingVO offering = diskOfferingDao.findById(volInfo.getDiskOfferingId());
        VMTemplateStoragePoolVO  tmpltStoredOn =  templatePoolDao.findByPoolTemplate(template.getDataStore().getId(), template.getId());
        
        DiskProfile diskProfile = new DiskProfile(volInfo, offering,
                null);
        CreateCommand cmd = new CreateCommand(diskProfile,
                tmpltStoredOn.getLocalDownloadPath(),
                new StorageFilerTO((StoragePool)template.getDataStore()));
        Answer answer = null;
        StoragePool pool = (StoragePool)volume.getDataStore();
        String errMsg = null;
        try {
            answer = storagMgr.sendToPool(pool, null, cmd);
        } catch (StorageUnavailableException e) {
            s_logger.debug("Failed to send to storage pool", e);
            errMsg = e.toString();
            return errMsg;
        }
        
        if (answer.getResult()) {
            VolumeVO vol = this.volDao.findById(volume.getId());
            CreateAnswer createAnswer = (CreateAnswer) answer;
            vol.setFolder(pool.getPath());
            vol.setPath(createAnswer.getVolume().getPath());
            vol.setSize(createAnswer.getVolume().getSize());
            vol.setPoolType(pool.getPoolType());
            vol.setPoolId(pool.getId());
            vol.setPodId(pool.getPodId());
            this.volDao.update(vol.getId(), vol);
           
        } else {
            if (tmpltStoredOn != null
                    && (answer instanceof CreateAnswer)
                    && ((CreateAnswer) answer)
                            .templateReloadRequested()) {
                if (!templateMgr
                        .resetTemplateDownloadStateOnPool(tmpltStoredOn
                                .getId())) {
                   
                }
            }
            errMsg = answer.getDetails();
        }
        
        return errMsg;
    }

    @Override
    public Void copyAsync(DataObject srcData, DataObject destData,
            AsyncCompletionCallback<CopyCommandResult> callback) {
        String errMsg = null;
        try {
            if (destData.getType() == DataObjectType.VOLUME
                    && srcData.getType() == DataObjectType.VOLUME) {
                errMsg = copyVolumeFromImage(srcData, destData);
            } else if (destData.getType() == DataObjectType.TEMPLATE
                    && srcData.getType() == DataObjectType.TEMPLATE) {
                copyTemplate(srcData, destData);
            } else if (srcData.getType() == DataObjectType.SNAPSHOT
                    && destData.getType() == DataObjectType.VOLUME) {
                errMsg = copyFromSnapshot(srcData, destData);
            } else if (srcData.getType() == DataObjectType.SNAPSHOT
                    && destData.getType() == DataObjectType.TEMPLATE) {
                errMsg = createTemplateFromSnashot(srcData, destData);
            } else if (srcData.getType() == DataObjectType.VOLUME
                    && destData.getType() == DataObjectType.TEMPLATE) {
                errMsg = createTemplateFromVolume(srcData, destData);
            } else if (srcData.getType() == DataObjectType.TEMPLATE 
                    && destData.getType() == DataObjectType.VOLUME) {
                errMsg = cloneVolume(srcData, destData);
            }
        } catch (Exception e) {
            s_logger.debug("copy failed", e);
            errMsg = e.toString();
        }
        CopyCommandResult result = new CopyCommandResult(null);
        result.setResult(errMsg);
        callback.complete(result);

        return null;
    }

    @DB
    protected String createTemplateFromSnashot(DataObject srcData,
            DataObject destData) {
        long snapshotId = srcData.getId();
        SnapshotVO snapshot = snapshotDao.findById(snapshotId);
        if (snapshot == null) {
            throw new CloudRuntimeException("Unable to find Snapshot for Id "
                    + srcData.getId());
        }
        Long zoneId = snapshot.getDataCenterId();
        HostVO secondaryStorageHost = this.templateMgr
                .getSecondaryStorageHost(zoneId);
        String secondaryStorageURL = snapshotMgr
                .getSecondaryStorageURL(snapshot);
        VMTemplateVO template = this.templateDao.findById(destData.getId());
        String name = template.getName();
        String backupSnapshotUUID = snapshot.getBackupSnapshotId();
        if (backupSnapshotUUID == null) {
            throw new CloudRuntimeException(
                    "Unable to create private template from snapshot "
                            + snapshotId
                            + " due to there is no backupSnapshotUUID for this snapshot");
        }

        Long dcId = snapshot.getDataCenterId();
        Long accountId = snapshot.getAccountId();
        Long volumeId = snapshot.getVolumeId();

        String origTemplateInstallPath = null;
        List<StoragePoolVO> pools = this.storagMgr
                .ListByDataCenterHypervisor(zoneId,
                        snapshot.getHypervisorType());
        if (pools == null || pools.size() == 0) {
            throw new CloudRuntimeException(
                    "Unable to find storage pools in zone " + zoneId);
        }
        StoragePoolVO poolvo = pools.get(0);
        StoragePool pool = (StoragePool) this.dataStoreMgr.getDataStore(
                poolvo.getId(), DataStoreRole.Primary);
        if (snapshot.getVersion() != null
                && snapshot.getVersion().equalsIgnoreCase("2.1")) {
            VolumeVO volume = this.volDao.findByIdIncludingRemoved(volumeId);
            if (volume == null) {
                throw new CloudRuntimeException("failed to upgrade snapshot "
                        + snapshotId + " due to unable to find orignal volume:"
                        + volumeId + ", try it later ");
            }
            if (volume.getTemplateId() == null) {
                snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
            } else {
                template = templateDao.findByIdIncludingRemoved(volume
                        .getTemplateId());
                if (template == null) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot "
                                    + snapshotId
                                    + " due to unalbe to find orignal template :"
                                    + volume.getTemplateId()
                                    + ", try it later ");
                }
                Long origTemplateId = template.getId();
                Long origTmpltAccountId = template.getAccountId();
                if (!this.volDao.lockInLockTable(volumeId.toString(), 10)) {
                    throw new CloudRuntimeException(
                            "failed to upgrade snapshot " + snapshotId
                                    + " due to volume:" + volumeId
                                    + " is being used, try it later ");
                }
                UpgradeSnapshotCommand cmd = new UpgradeSnapshotCommand(null,
                        secondaryStorageURL, dcId, accountId, volumeId,
                        origTemplateId, origTmpltAccountId, null,
                        snapshot.getBackupSnapshotId(), snapshot.getName(),
                        "2.1");
                if (!this.volDao.lockInLockTable(volumeId.toString(), 10)) {
                    throw new CloudRuntimeException(
                            "Creating template failed due to volume:"
                                    + volumeId
                                    + " is being used, try it later ");
                }
                Answer answer = null;
                try {
                    answer = this.storagMgr.sendToPool(pool, cmd);
                    cmd = null;
                } catch (StorageUnavailableException e) {
                } finally {
                    this.volDao.unlockFromLockTable(volumeId.toString());
                }
                if ((answer != null) && answer.getResult()) {
                    snapshotDao.updateSnapshotVersion(volumeId, "2.1", "2.2");
                } else {
                    throw new CloudRuntimeException(
                            "Unable to upgrade snapshot");
                }
            }
        }
        if (snapshot.getSwiftId() != null && snapshot.getSwiftId() != 0) {
            snapshotMgr.downloadSnapshotsFromSwift(snapshot);
        }
        String value = configDao
                .getValue(Config.CreatePrivateTemplateFromSnapshotWait
                        .toString());
        int _createprivatetemplatefromsnapshotwait = NumbersUtil.parseInt(
                value, Integer
                        .parseInt(Config.CreatePrivateTemplateFromSnapshotWait
                                .getDefaultValue()));

        CreatePrivateTemplateFromSnapshotCommand cmd = new CreatePrivateTemplateFromSnapshotCommand(
                pool, secondaryStorageURL, dcId, accountId,
                snapshot.getVolumeId(), backupSnapshotUUID, snapshot.getName(),
                origTemplateInstallPath, template.getId(), name,
                _createprivatetemplatefromsnapshotwait);

        return sendCommand(cmd, pool, template.getId(), dcId,
                secondaryStorageHost.getId());
    }

    @DB
    protected String sendCommand(Command cmd, StoragePool pool,
            long templateId, long zoneId, long hostId) {

        CreatePrivateTemplateAnswer answer = null;
        try {
            answer = (CreatePrivateTemplateAnswer) this.storagMgr.sendToPool(
                    pool, cmd);
        } catch (StorageUnavailableException e) {
            throw new CloudRuntimeException(
                    "Failed to execute CreatePrivateTemplateFromSnapshotCommand",
                    e);
        }

        if (answer == null) {
            return "Failed to execute CreatePrivateTemplateFromSnapshotCommand";
        } else if (!answer.getResult()) {
            return "Failed to execute CreatePrivateTemplateFromSnapshotCommand"
                    + answer.getDetails();
        }

        VMTemplateVO privateTemplate = templateDao.findById(templateId);
        String answerUniqueName = answer.getUniqueName();
        if (answerUniqueName != null) {
            privateTemplate.setUniqueName(answerUniqueName);
        }
        ImageFormat format = answer.getImageFormat();
        if (format != null) {
            privateTemplate.setFormat(format);
        } else {
            // This never occurs.
            // Specify RAW format makes it unusable for snapshots.
            privateTemplate.setFormat(ImageFormat.RAW);
        }

        String checkSum = this.templateMgr
                .getChecksum(hostId, answer.getPath());

        Transaction txn = Transaction.currentTxn();

        txn.start();

        privateTemplate.setChecksum(checkSum);
        templateDao.update(privateTemplate.getId(), privateTemplate);

        // add template zone ref for this template
        templateDao.addTemplateToZone(privateTemplate, zoneId);
        VMTemplateHostVO templateHostVO = new VMTemplateHostVO(hostId,
                privateTemplate.getId());
        templateHostVO.setDownloadPercent(100);
        templateHostVO.setDownloadState(Status.DOWNLOADED);
        templateHostVO.setInstallPath(answer.getPath());
        templateHostVO.setLastUpdated(new Date());
        templateHostVO.setSize(answer.getVirtualSize());
        templateHostVO.setPhysicalSize(answer.getphysicalSize());
        templateHostDao.persist(templateHostVO);
        txn.close();
        return null;
    }

    private String createTemplateFromVolume(DataObject srcObj,
            DataObject destObj) {
        long volumeId = srcObj.getId();
        VolumeVO volume = this.volDao.findById(volumeId);
        if (volume == null) {
            throw new CloudRuntimeException("Unable to find volume for Id "
                    + volumeId);
        }
        long accountId = volume.getAccountId();

        String vmName = this.volumeMgr.getVmNameOnVolume(volume);
        Long zoneId = volume.getDataCenterId();
        HostVO secondaryStorageHost = this.templateMgr
                .getSecondaryStorageHost(zoneId);
        if (secondaryStorageHost == null) {
            throw new CloudRuntimeException(
                    "Can not find the secondary storage for zoneId " + zoneId);
        }
        String secondaryStorageURL = secondaryStorageHost.getStorageUrl();
        VMTemplateVO template = this.templateDao.findById(destObj.getId());
        StoragePool pool = (StoragePool) this.dataStoreMgr.getDataStore(
                volume.getPoolId(), DataStoreRole.Primary);
        String value = configDao
                .getValue(Config.CreatePrivateTemplateFromVolumeWait.toString());
        int _createprivatetemplatefromvolumewait = NumbersUtil.parseInt(value,
                Integer.parseInt(Config.CreatePrivateTemplateFromVolumeWait
                        .getDefaultValue()));

        CreatePrivateTemplateFromVolumeCommand cmd = new CreatePrivateTemplateFromVolumeCommand(
                pool, secondaryStorageURL, destObj.getId(), accountId,
                template.getName(), template.getUniqueName(), volume.getPath(),
                vmName, _createprivatetemplatefromvolumewait);

        return sendCommand(cmd, pool, template.getId(), zoneId,
                secondaryStorageHost.getId());
    }

}
