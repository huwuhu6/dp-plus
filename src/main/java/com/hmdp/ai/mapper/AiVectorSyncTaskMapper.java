package com.hmdp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.ai.entity.AiVectorSyncTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiVectorSyncTaskMapper extends BaseMapper<AiVectorSyncTask> {
    @Insert("""
            INSERT INTO tbl_ai_vector_sync_task
              (document_id, document_type, entity_id, shop_id, operation, target_revision, status, available_at)
            VALUES
              (#{task.documentId}, #{task.documentType}, #{task.entityId}, #{task.shopId}, #{task.operation},
               #{task.targetRevision}, 'PENDING', NOW(3))
            ON DUPLICATE KEY UPDATE
              status = IF(
                (VALUES(target_revision) > target_revision
                 OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'))
                AND status <> 'SYNCING', 'PENDING', status),
              available_at = IF(
                (VALUES(target_revision) > target_revision
                 OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'))
                AND status <> 'SYNCING', NOW(3), available_at),
              last_error = IF(
                VALUES(target_revision) > target_revision
                OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'),
                NULL, last_error),
              document_type = IF(
                VALUES(target_revision) > target_revision
                OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'),
                VALUES(document_type), document_type),
              entity_id = IF(
                VALUES(target_revision) > target_revision
                OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'),
                VALUES(entity_id), entity_id),
              shop_id = IF(
                VALUES(target_revision) > target_revision
                OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'),
                VALUES(shop_id), shop_id),
              operation = IF(
                VALUES(target_revision) > target_revision
                OR (VALUES(target_revision) = target_revision AND VALUES(operation) = 'DELETE' AND operation <> 'DELETE'),
                VALUES(operation), operation),
              target_revision = GREATEST(target_revision, VALUES(target_revision))
            """)
    int mergeLatest(@Param("task") AiVectorSyncTask task);

    @Update("""
            UPDATE tbl_ai_vector_sync_task
            SET status = 'SYNCING', lease_token = #{leaseToken}, leased_at = #{now},
                attempt_count = attempt_count + 1, last_error = NULL
            WHERE id = #{id}
              AND status = 'PENDING'
              AND operation = #{operation}
              AND target_revision = #{targetRevision}
              AND available_at <= #{now}
            """)
    int claim(@Param("id") Long id, @Param("operation") String operation,
              @Param("targetRevision") Long targetRevision, @Param("leaseToken") String leaseToken,
              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tbl_ai_vector_sync_task
            SET status = 'SYNCED', lease_token = NULL, leased_at = NULL, last_error = NULL
            WHERE document_id = #{documentId}
              AND operation = #{operation}
              AND target_revision = #{targetRevision}
              AND status = 'SYNCING'
              AND lease_token = #{leaseToken}
            """)
    int markSynced(@Param("documentId") String documentId, @Param("operation") String operation,
                   @Param("targetRevision") Long targetRevision, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE tbl_ai_vector_sync_task
            SET status = 'PENDING', lease_token = NULL, leased_at = NULL, available_at = #{availableAt},
                last_error = #{error}
            WHERE document_id = #{documentId}
              AND operation = #{operation}
              AND target_revision = #{targetRevision}
              AND status = 'SYNCING'
              AND lease_token = #{leaseToken}
            """)
    int retryClaim(@Param("documentId") String documentId, @Param("operation") String operation,
                   @Param("targetRevision") Long targetRevision, @Param("leaseToken") String leaseToken,
                   @Param("availableAt") LocalDateTime availableAt, @Param("error") String error);

    @Update("""
            UPDATE tbl_ai_vector_sync_task
            SET status = 'PENDING', lease_token = NULL, leased_at = NULL, available_at = #{availableAt}
            WHERE document_id = #{documentId}
              AND status = 'SYNCING'
              AND lease_token = #{leaseToken}
              AND (operation <> #{operation} OR target_revision <> #{targetRevision})
            """)
    int requeueNewerState(@Param("documentId") String documentId, @Param("operation") String operation,
                          @Param("targetRevision") Long targetRevision, @Param("leaseToken") String leaseToken,
                          @Param("availableAt") LocalDateTime availableAt);

    @Update("""
            UPDATE tbl_ai_vector_sync_task
            SET status = 'PENDING', available_at = NOW(3), last_error = 'reconciliation detected drift'
            WHERE document_id = #{documentId}
              AND operation = #{operation}
              AND target_revision = #{targetRevision}
              AND status = 'SYNCED'
            """)
    int requeueSyncedDesired(@Param("documentId") String documentId, @Param("operation") String operation,
                             @Param("targetRevision") Long targetRevision);

    @Update("""
            UPDATE tbl_ai_vector_sync_task
            SET status = 'PENDING', lease_token = NULL, leased_at = NULL, available_at = #{now},
                last_error = 'lease expired'
            WHERE status = 'SYNCING' AND leased_at < #{expiredBefore}
            """)
    int recoverExpiredLeases(@Param("expiredBefore") LocalDateTime expiredBefore, @Param("now") LocalDateTime now);
}
