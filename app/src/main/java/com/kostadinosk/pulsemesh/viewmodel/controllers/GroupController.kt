// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.viewmodel.controllers

import com.kostadinosk.pulsemesh.crypto.TacticalE2eeManager
import com.kostadinosk.pulsemesh.model.MeshGroup
import com.kostadinosk.pulsemesh.util.DevicePreferences

/**
 * Manages Group Admin & Member Management (add/remove member, promote/demote admin,
 * and automatic `keyEpoch` group-key rotation on membership changes).
 */
class GroupController(
    private val prefs: DevicePreferences
) {
    fun addMemberAndRotateKey(group: MeshGroup, newMemberId: String): MeshGroup {
        val nextMembers = (group.memberIds + newMemberId.trim()).distinct()
        val (nextEpoch, nextKey) = TacticalE2eeManager.rotateGroupKey(group.keyEpoch)
        val updated = group.copy(
            memberIds = nextMembers,
            keyEpoch = nextEpoch,
            groupKeyBase64 = nextKey
        )
        prefs.saveGroupAesKey(group.groupId, nextKey)
        saveGroup(updated)
        return updated
    }

    fun removeMemberAndRotateKey(group: MeshGroup, memberIdToRemove: String): MeshGroup {
        val normRemove = memberIdToRemove.trim().uppercase().removePrefix("0X")
        val nextMembers = group.memberIds.filterNot { it.trim().uppercase().removePrefix("0X") == normRemove }
        val nextAdmins = group.adminIds.filterNot { it.trim().uppercase().removePrefix("0X") == normRemove }
        val (nextEpoch, nextKey) = TacticalE2eeManager.rotateGroupKey(group.keyEpoch)
        val updated = group.copy(
            memberIds = nextMembers,
            adminIds = nextAdmins,
            keyEpoch = nextEpoch,
            groupKeyBase64 = nextKey
        )
        prefs.saveGroupAesKey(group.groupId, nextKey)
        saveGroup(updated)
        return updated
    }

    fun toggleAdminRole(group: MeshGroup, memberId: String): MeshGroup {
        val norm = memberId.trim().uppercase().removePrefix("0X")
        val isAdmin = group.adminIds.any { it.trim().uppercase().removePrefix("0X") == norm }
        val nextAdmins = if (isAdmin) {
            group.adminIds.filterNot { it.trim().uppercase().removePrefix("0X") == norm }
        } else {
            (group.adminIds + memberId.trim()).distinct()
        }
        return group.copy(adminIds = nextAdmins).also { saveGroup(it) }
    }

    fun buildGroupInviteQrPayload(group: MeshGroup): String {
        return org.json.JSONObject().apply {
            put("pmGroupInvite", 2)
            put("groupId", group.groupId)
            put("groupName", group.groupName)
            put("epoch", group.keyEpoch)
            put("members", group.memberIds.joinToString(","))
            if (group.groupKeyBase64.isNotBlank()) put("key", group.groupKeyBase64)
        }.toString()
    }

    fun rotateGroupKey(group: MeshGroup): MeshGroup {
        val (nextEpoch, nextKey) = TacticalE2eeManager.rotateGroupKey(group.keyEpoch)
        val updated = group.copy(keyEpoch = nextEpoch, groupKeyBase64 = nextKey)
        prefs.saveGroupAesKey(group.groupId, nextKey)
        saveGroup(updated)
        return updated
    }

    private fun saveGroup(group: MeshGroup) {
        val all = prefs.loadMeshGroups().filterNot { it.groupId == group.groupId } + group
        prefs.saveMeshGroups(all)
    }
}
