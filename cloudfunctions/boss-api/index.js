const cloudbase = require('@cloudbase/node-sdk');

const app = cloudbase.init({
  env: cloudbase.SYMBOL_CURRENT_ENV,
});

const db = app.database();
const _ = db.command;

const ROOMS = 'rooms';
const BOSSES = 'bosses';
const MEMBERS = 'members';

// --- helpers ---

function pad3(n) { return String(n).padStart(3, '0'); }

function genUserId() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let r = '';
  for (let i = 0; i < 6; i++) r += chars.charAt(Math.floor(Math.random() * chars.length));
  return r;
}

function jsonResp(data) {
  return {
    statusCode: 200,
    headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' },
    body: JSON.stringify(data),
  };
}

function errResp(msg, detail) {
  return {
    statusCode: 400,
    headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' },
    body: JSON.stringify({ error: msg, detail: detail || '' }),
  };
}

async function getRoom(roomId) {
  const r = await db.collection(ROOMS).where({ roomId }).get();
  return r.data.length > 0 ? r.data[0] : null;
}

async function getMember(roomId, userId) {
  const r = await db.collection(MEMBERS).where({ roomId, userId }).get();
  return r.data.length > 0 ? r.data[0] : null;
}

async function bumpVersion(roomId) {
  await db.collection(ROOMS).where({ roomId }).update({ version: _.inc(1), updatedAt: Date.now() });
}

// --- permission check ---

// Roles: owner, admin, member
// Permissions object on member doc: { canReset: true, canEdit: true, canAdd: true, canDelete: true }
function rolePriority(role) {
  if (role === 'owner') return 4;
  if (role === 'super_admin') return 3;
  if (role === 'admin') return 2;
  return 1;
}

function canDo(member, action) {
  if (!member) return false;
  if (member.role === 'owner' || member.role === 'super_admin') return true;
  if (member.role === 'admin' && (action === 'reset' || action === 'edit')) return true;
  if (member.permissions && member.permissions[action]) return true;
  return false;
}

// --- handlers ---

async function registerUser(params) {
  const { name } = params;
  if (!name) return errResp('name is required');

  let userId;
  for (let i = 0; i < 20; i++) {
    userId = genUserId();
    const existing = await db.collection(MEMBERS).where({ userId }).limit(1).get();
    if (existing.data.length === 0) break;
  }
  if (!userId) return errResp('failed to generate userId');

  return jsonResp({ userId, name });
}

async function createRoom(params) {
  const { userId, userName, roomName, password } = params;
  if (!userId) return errResp('userId is required');
  if (!userName) return errResp('userName is required');

  const countRes = await db.collection(ROOMS).where({ ownerUserId: userId }).count();
  const codeRes = await db.collection('settings').where({ key: 'expansion_codes' }).limit(1).get();
  const validCodes = codeRes.data.length > 0 ? (codeRes.data[0].codes || []) : [];
  const useCode = params.expansionCode;

  if (countRes.total >= 2) {
    if (!useCode || !validCodes.includes(useCode)) return errResp('need_expansion_code');
    const newCodes = validCodes.filter(c => c !== useCode);
    await db.collection('settings').doc(codeRes.data[0]._id).update({ codes: newCodes });
  }

  let roomId;
  for (let i = 0; i < 100; i++) {
    const n = Math.floor(Math.random() * 900) + 100;
    roomId = String(n);
    const existing = await getRoom(roomId);
    if (!existing) break;
    roomId = null;
  }
  if (!roomId) return errResp('no available roomId');

  await db.collection(ROOMS).add({
    roomId,
    roomName: roomName || 'Boss Room',
    ownerUserId: userId,
    password: password || null,
    version: 0,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  });

  // add owner as member
  await db.collection(MEMBERS).add({
    roomId,
    userId,
    name: userName,
    role: 'owner',
    permissions: {},
    joinedAt: Date.now(),
  });

  return jsonResp({ roomId, roomName, version: 0 });
}

async function joinRoom(params) {
  const { roomId, userId, userName, password } = params;
  if (!roomId) return errResp('roomId is required');
  if (!userId) return errResp('userId is required');
  if (!userName) return errResp('userName is required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');

  if (room.password && room.ownerUserId !== userId && room.password !== (password || '')) {
    return errResp('wrong password');
  }

  if (room.banned && room.banned.includes(userId)) {
    return errResp('banned');
  }

  let member = await getMember(roomId, userId);
  if (!member) {
    await db.collection(MEMBERS).add({
      roomId,
      userId,
      name: userName,
      role: 'member',
      permissions: {},
      joinedAt: Date.now(),
    });
    member = await getMember(roomId, userId);
    try { await db.collection('logs').add({
      roomId, userId, userName, action: 'join',
      target: userName, time: Date.now(),
    }); } catch(e) {}
  } else if (userName && userName !== member.name) {
    await db.collection(MEMBERS).where({ roomId, userId }).update({ name: userName });
    member = await getMember(roomId, userId);
  }

  return jsonResp({
    roomId: room.roomId,
    roomName: room.roomName,
    version: room.version,
    role: member.role,
    permissions: member.permissions || {},
  });
}

async function getRoomInfo(params) {
  const { roomId, userId } = params;
  if (!roomId) return errResp('roomId is required');
  if (!userId) return errResp('userId is required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');

  const member = await getMember(roomId, userId);

  const membersRes = await db.collection(MEMBERS).where({ roomId }).get();
  const members = membersRes.data.map(m => ({
    userId: m.userId,
    name: m.name,
    role: m.role,
    permissions: m.permissions || {},
  }));

  return jsonResp({
    roomId: room.roomId,
    roomName: room.roomName,
    ownerUserId: room.ownerUserId,
    version: room.version,
    role: member ? member.role : null,
    permissions: member ? (member.permissions || {}) : {},
    members,
  });
}

async function updateMemberRole(params) {
  const { roomId, ownerUserId, targetUserId, role, permissions } = params;
  if (!roomId) return errResp('roomId is required');
  if (!ownerUserId) return errResp('ownerUserId is required');
  if (!targetUserId) return errResp('targetUserId is required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  if (room.ownerUserId !== ownerUserId) return errResp('only owner can change roles');
  if (role === 'owner') return errResp('cannot change to owner role');
  if (targetUserId === ownerUserId) return errResp('cannot change your own role');

  const targetMember = await getMember(roomId, targetUserId);
  const oldRole = targetMember ? targetMember.role : 'member';
  const targetName = targetMember ? targetMember.name : targetUserId;

  const updateData = {};
  if (role) updateData.role = role;
  if (permissions) updateData.permissions = permissions;

  await db.collection(MEMBERS).where({ roomId, userId: targetUserId }).update(updateData);
  await bumpVersion(roomId);

  const roleNames = { owner: '房主', super_admin: '超管', admin: '管理员', member: '成员' };
  const ownerMember = await getMember(roomId, ownerUserId);
  const ownerName = ownerMember ? ownerMember.name : ownerUserId;
  try { await db.collection('logs').add({
    roomId, userId: ownerUserId, userName: ownerName, action: 'role',
    target: targetName, targetUserId: targetUserId, time: Date.now(),
    changes: [{ field: 'role', old: roleNames[oldRole] || oldRole, new: roleNames[role] || role }],
  }); } catch(e) {}

  return jsonResp({ success: true });
}

async function getBosses(params) {
  const { roomId, userId } = params;
  if (!roomId) return errResp('roomId is required');
  if (!userId) return errResp('userId is required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');

  const member = await getMember(roomId, userId);
  if (!member) return errResp('not a member of this room');

  const res = await db.collection(BOSSES).where({ roomId }).orderBy('createdAt', 'asc').get();

  const bosses = res.data.map(b => ({
    docId: b._id,
    name: b.name,
    spawn: b.spawn,
    extra: b.extra || '',
    startTime: b.startTime,
    notifyTime: b.notifyTime,
    needNotify: b.needNotify !== false,
    autoReset: b.autoReset !== false,
    showInFloat: b.showInFloat !== false,
    decreasingMode: b.decreasingMode || false,
    decreasingSeconds: b.decreasingSeconds || 0,
    decreasingCount: b.decreasingCount || 0,
    deathCount: b.deathCount || 0,
    initialSpawnTime: b.initialSpawnTime || 0,
  }));

  return jsonResp({
    roomId: room.roomId,
    version: room.version,
    role: member.role,
    permissions: member.permissions || {},
    bosses,
  });
}

async function addBoss(params) {
  const { roomId, userId, boss } = params;
  if (!roomId) return errResp('roomId is required');
  if (!userId) return errResp('userId is required');
  if (!boss) return errResp('boss data is required');

  const member = await getMember(roomId, userId);
  if (!member) return errResp('not a member');
  if (!canDo(member, 'canAdd') && member.role !== 'owner' && member.role !== 'super_admin')
    return errResp('no permission to add boss');

  const bossCount = await db.collection(BOSSES).where({ roomId }).count();
  const codeRes = await db.collection('settings').where({ key: 'expansion_codes' }).limit(1).get();
  const validCodes = codeRes.data.length > 0 ? (codeRes.data[0].codes || []) : [];
  const useCode = boss.expansionCode;

  if (bossCount.total >= 20) {
    if (!useCode || !validCodes.includes(useCode)) return errResp('need_expansion_code');
    const newCodes = validCodes.filter(c => c !== useCode);
    await db.collection('settings').doc(codeRes.data[0]._id).update({ codes: newCodes });
  }

  const addRes = await db.collection(BOSSES).add({
    roomId,
    name: boss.name || '',
    spawn: boss.spawn || 0,
    extra: boss.extra || '',
    startTime: boss.startTime || 0,
    notifyTime: boss.notifyTime || 300,
    needNotify: boss.needNotify !== false,
    autoReset: boss.autoReset !== false,
    showInFloat: boss.showInFloat !== false,
    decreasingMode: boss.decreasingMode || false,
    decreasingSeconds: boss.decreasingSeconds || 0,
    decreasingCount: boss.decreasingCount || 0,
    deathCount: boss.deathCount || 0,
    initialSpawnTime: boss.initialSpawnTime || 0,
    lastModifier: userId,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  });

  await bumpVersion(roomId);
  const room = await getRoom(roomId);

  try { await db.collection('logs').add({
    roomId, userId, userName: member.name, action: 'add', target: boss.name,
    spawn: boss.spawn || 0, refreshTime: (boss.startTime||0) + (boss.spawn||0)*1000, time: Date.now(),
  }); } catch(e) {}

  return jsonResp({ docId: addRes.id, version: room.version });
}

async function updateBoss(params) {
  const { roomId, userId, docId, boss } = params;
  if (!roomId || !userId || !docId || !boss) return errResp('roomId, userId, docId, boss are required');

  const member = await getMember(roomId, userId);
  if (!member) return errResp('not a member');
  if (!canDo(member, 'canEdit') && member.role !== 'owner' && member.role !== 'admin')
    return errResp('no permission to edit boss');

  const bossDoc = await db.collection(BOSSES).doc(docId).get();
  if (bossDoc.data.length > 0 && bossDoc.data[0].lastModifier && bossDoc.data[0].updatedAt) {
    const lastMod = bossDoc.data[0].lastModifier;
    const elapsed = Date.now() - bossDoc.data[0].updatedAt;
    if (elapsed < 10000 && lastMod !== userId) {
      const lastMember = await getMember(roomId, lastMod);
      if (lastMember && rolePriority(lastMember.role) > rolePriority(member.role)) {
        return errResp('higher_priority_locked');
      }
    }
  }

  const up = { updatedAt: Date.now(), lastModifier: userId };
  if (boss.name !== undefined) up.name = boss.name;
  if (boss.spawn !== undefined) up.spawn = boss.spawn;
  if (boss.extra !== undefined) up.extra = boss.extra;
  if (boss.startTime !== undefined) up.startTime = boss.startTime;
  if (boss.notifyTime !== undefined) up.notifyTime = boss.notifyTime;
  if (boss.needNotify !== undefined) up.needNotify = boss.needNotify;
  if (boss.autoReset !== undefined) up.autoReset = boss.autoReset;
  if (boss.showInFloat !== undefined) up.showInFloat = boss.showInFloat;
  if (boss.decreasingMode !== undefined) up.decreasingMode = boss.decreasingMode;
  if (boss.decreasingSeconds !== undefined) up.decreasingSeconds = boss.decreasingSeconds;
  if (boss.decreasingCount !== undefined) up.decreasingCount = boss.decreasingCount;
  if (boss.deathCount !== undefined) up.deathCount = boss.deathCount;
  if (boss.initialSpawnTime !== undefined) up.initialSpawnTime = boss.initialSpawnTime;

  await db.collection(BOSSES).doc(docId).update(up);
  await bumpVersion(roomId);
  const room = await getRoom(roomId);

  const bossName = bossDoc.data.length > 0 ? bossDoc.data[0].name : '';
  const old = bossDoc.data.length > 0 ? bossDoc.data[0] : {};
  const spawn = old.spawn || 0;
  const changes = [];
  if (boss.startTime !== undefined && boss.startTime !== old.startTime) {
    const stChange = { field: 'startTime', old: old.startTime || 0, new: boss.startTime,
      oldRefresh: (old.startTime || 0) + spawn * 1000, newRefresh: boss.startTime + spawn * 1000,
      spawn: spawn };
    if (boss.enteredValue !== undefined) stChange.enteredValue = boss.enteredValue;
    changes.push(stChange);
  }
  if (boss.name !== undefined && boss.name !== old.name) changes.push({ field: 'name', old: old.name || '', new: boss.name });
  if (boss.notifyTime !== undefined && boss.notifyTime !== old.notifyTime) changes.push({ field: 'notifyTime', old: old.notifyTime || 0, new: boss.notifyTime });
  if (boss.autoReset !== undefined && boss.autoReset !== old.autoReset) changes.push({ field: 'autoReset', old: old.autoReset, new: boss.autoReset });
  if (boss.spawn !== undefined && boss.spawn !== old.spawn) changes.push({ field: 'spawn', old: old.spawn || 0, new: boss.spawn });
  if (boss.showInFloat !== undefined && boss.showInFloat !== old.showInFloat) changes.push({ field: 'showInFloat', old: !!old.showInFloat, new: !!boss.showInFloat });
  if (boss.decreasingMode !== undefined && boss.decreasingMode !== old.decreasingMode) changes.push({ field: 'decreasingMode', old: !!old.decreasingMode, new: !!boss.decreasingMode });
  if (boss.decreasingSeconds !== undefined && boss.decreasingSeconds !== old.decreasingSeconds) changes.push({ field: 'decreasingSeconds', old: old.decreasingSeconds || 0, new: boss.decreasingSeconds });
  if (boss.decreasingCount !== undefined && boss.decreasingCount !== old.decreasingCount) changes.push({ field: 'decreasingCount', old: old.decreasingCount || 0, new: boss.decreasingCount });
  if (changes.length > 0 && (!boss.action || boss.action === 'edit')) {
    try { await db.collection('logs').add({
      roomId, userId, userName: member.name, action: 'edit', target: bossName || docId,
      changes: changes, time: Date.now(),
      editTimeType: boss.editTimeType || '',
    }); } catch(e) {}
  }

  return jsonResp({ version: room.version });
}

async function deleteBoss(params) {
  const { roomId, userId, docId } = params;
  if (!roomId || !userId || !docId) return errResp('roomId, userId, docId are required');

  const member = await getMember(roomId, userId);
  if (!member) return errResp('not a member');
  if (!canDo(member, 'canDelete') && member.role !== 'owner')
    return errResp('no permission to delete boss');

  const bossDoc = await db.collection(BOSSES).doc(docId).get();
  const b = bossDoc.data.length > 0 ? bossDoc.data[0] : {};
  await db.collection(BOSSES).doc(docId).remove();
  await bumpVersion(roomId);
  const room = await getRoom(roomId);

  try { await db.collection('logs').add({
    roomId, userId, userName: member.name, action: 'delete',
    target: b.name || docId, spawn: b.spawn || 0,
    refreshTime: (b.startTime || 0) + (b.spawn || 0) * 1000, time: Date.now(),
    decreasing: b.decreasingMode || false,
    decreasingSeconds: b.decreasingSeconds || 0,
    decreasingCount: b.decreasingCount || 0,
  }); } catch(e) {}

  return jsonResp({ version: room.version });
}

async function getRoomVersion(params) {
  const { roomId } = params;
  if (!roomId) return errResp('roomId is required');
  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  return jsonResp({ version: room.version });
}

async function setRoomPassword(params) {
  const { roomId, userId, password } = params;
  if (!roomId || !userId) return errResp('roomId, userId are required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  if (room.ownerUserId !== userId) return errResp('only owner can change password');

  await db.collection(ROOMS).where({ roomId }).update({
    password: password || null,
    updatedAt: Date.now(),
  });

  return jsonResp({ hasPassword: !!password });
}

async function getRoomPassword(params) {
  const { roomId, userId } = params;
  if (!roomId || !userId) return errResp('roomId, userId are required');

  const member = await getMember(roomId, userId);
  if (!member) return errResp('not a member');

  const room = await getRoom(roomId);
  const resp = { hasPassword: !!room.password };
  if (member.role === 'owner' && room.password) {
    resp.password = room.password;
  }
  return jsonResp(resp);
}

async function getMyRooms(params) {
  const { userId } = params;
  if (!userId) return errResp('userId is required');

  const memRes = await db.collection(MEMBERS).where({ userId }).get();
  const roomIds = memRes.data.map(m => m.roomId);
  const roleMap = {};
  memRes.data.forEach(m => { roleMap[m.roomId] = m.role; });

  if (roomIds.length === 0) return jsonResp({ rooms: [] });

  const res = await db.collection(ROOMS).where({ roomId: _.in(roomIds) }).get();
  const rooms = res.data.map(r => ({
    roomId: r.roomId,
    roomName: r.roomName,
    hasPassword: !!r.password,
    version: r.version,
    createdAt: r.createdAt,
    role: roleMap[r.roomId] || 'member',
  }));
  return jsonResp({ rooms });
}

async function deleteRoom(params) {
  const { roomId, userId } = params;
  if (!roomId || !userId) return errResp('roomId, userId are required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  if (room.ownerUserId !== userId) return errResp('only owner can delete room');

  await db.collection(BOSSES).where({ roomId }).remove();
  await db.collection(MEMBERS).where({ roomId }).remove();
  await db.collection(ROOMS).where({ roomId }).remove();

  return jsonResp({ success: true });
}

async function updateRoomInfo(params) {
  const { roomId, userId, roomName, password } = params;
  if (!roomId || !userId) return errResp('roomId, userId are required');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  if (room.ownerUserId !== userId) return errResp('only owner can update room');

  const up = { updatedAt: Date.now() };
  if (roomName !== undefined) up.roomName = roomName;
  if (password !== undefined) up.password = password || null;

  await db.collection(ROOMS).where({ roomId }).update(up);
  await bumpVersion(roomId);
  return jsonResp({ success: true });
}

async function verifyAuth(params) {
  const { code } = params;
  if (!code) return errResp('code is required');
  const res = await db.collection('settings').where({ key: 'authCode' }).limit(1).get();
  const validCode = res.data.length > 0 ? res.data[0].value : '123456';
  return jsonResp({ valid: code === validCode });
}

async function kickMember(params) {
  const { roomId, ownerUserId, targetUserId } = params;
  if (!roomId || !ownerUserId || !targetUserId) return errResp('roomId, ownerUserId, targetUserId required');
  if (targetUserId === ownerUserId) return errResp('cannot kick yourself');

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  if (room.ownerUserId === targetUserId) return errResp('cannot kick room owner');

  const kicker = await getMember(roomId, ownerUserId);
  if (!kicker || (kicker.role !== 'owner' && kicker.role !== 'super_admin'))
    return errResp('no permission to kick');

  const targetMember = await getMember(roomId, targetUserId);
  const targetName = targetMember ? targetMember.name : targetUserId;

  await db.collection(MEMBERS).where({ roomId, userId: targetUserId }).remove();

  await db.collection(ROOMS).where({ roomId }).update({
    banned: _.push([targetUserId]),
  });

  try { await db.collection('logs').add({
    roomId,
    userId: ownerUserId,
    userName: kicker.name,
    action: 'kick',
    target: targetName + ' (' + targetUserId + ')',
    time: Date.now(),
  }); } catch(e) {}

  await bumpVersion(roomId);
  return jsonResp({ success: true });
}

async function getBannedList(params) {
  const { roomId, userId } = params;
  if (!roomId || !userId) return errResp('roomId, userId required');
  const member = await getMember(roomId, userId);
  if (!member || (member.role !== 'owner' && member.role !== 'super_admin'))
    return errResp('no permission');
  const room = await getRoom(roomId);
  return jsonResp({ banned: room.banned || [] });
}

async function unbanMember(params) {
  const { roomId, userId, targetUserId } = params;
  if (!roomId || !userId || !targetUserId) return errResp('roomId, userId, targetUserId required');
  const member = await getMember(roomId, userId);
  if (!member || (member.role !== 'owner' && member.role !== 'super_admin'))
    return errResp('no permission');
  const room = await getRoom(roomId);
  const banned = (room.banned || []).filter(id => id !== targetUserId);
  await db.collection(ROOMS).where({ roomId }).update({ banned });
  return jsonResp({ success: true });
}

async function getLogs(params) {
  const { roomId, userId } = params;
  if (!roomId || !userId) return errResp('roomId, userId required');
  const member = await getMember(roomId, userId);
  if (!member || (member.role !== 'owner' && member.role !== 'super_admin' && member.role !== 'admin'))
    return errResp('no permission');
  const res = await db.collection('logs').where({ roomId }).orderBy('time', 'desc').limit(50).get();
  return jsonResp({ logs: res.data });
}

async function addLog(params) {
  const { roomId, userId, userName, action, target, time, spawn, refreshTime, changes, bosses } = params;
  if (!roomId || !userId || !action) return errResp('roomId, userId, action required');
  const logData = { roomId, userId, userName: userName || '', action, target: target || '', time: time || Date.now() };
  if (spawn !== undefined) logData.spawn = spawn;
  if (refreshTime !== undefined) logData.refreshTime = refreshTime;
  if (changes !== undefined) logData.changes = changes;
  if (bosses !== undefined) logData.bosses = bosses;
  // 透传额外字段
  const known = ['roomId','userId','userName','action','target','time','spawn','refreshTime','changes','bosses'];
  for (const key of Object.keys(params)) {
    if (!known.includes(key) && params[key] !== undefined) logData[key] = params[key];
  }
  await db.collection('logs').add(logData);
  return jsonResp({ success: true });
}

async function setAuthCode(params) {
  const { code, masterKey } = params;
  if (masterKey !== 'boss2024admin') return errResp('invalid master key');
  const res = await db.collection('settings').where({ key: 'authCode' }).get();
  if (res.data.length > 0) {
    await db.collection('settings').doc(res.data[0]._id).update({ value: code });
  } else {
    await db.collection('settings').add({ key: 'authCode', value: code });
  }
  return jsonResp({ success: true });
}

// --- router ---

const ROUTES = {
  '/registerUser':     { h: registerUser,     m: 'POST' },
  '/createRoom':       { h: createRoom,       m: 'POST' },
  '/joinRoom':         { h: joinRoom,         m: 'POST' },
  '/getRoomInfo':      { h: getRoomInfo,      m: 'GET' },
  '/updateMemberRole': { h: updateMemberRole, m: 'POST' },
  '/getBosses':        { h: getBosses,        m: 'GET' },
  '/addBoss':          { h: addBoss,          m: 'POST' },
  '/updateBoss':       { h: updateBoss,       m: 'POST' },
  '/deleteBoss':       { h: deleteBoss,       m: 'POST' },
  '/getRoomVersion':   { h: getRoomVersion,   m: 'GET' },
  '/setRoomPassword':  { h: setRoomPassword,  m: 'POST' },
  '/getRoomPassword':  { h: getRoomPassword,  m: 'GET' },
  '/getMyRooms':       { h: getMyRooms,       m: 'GET' },
  '/deleteRoom':       { h: deleteRoom,       m: 'POST' },
  '/updateRoomInfo':   { h: updateRoomInfo,   m: 'POST' },
  '/verifyAuth':       { h: verifyAuth,       m: 'POST' },
  '/setAuthCode':      { h: setAuthCode,      m: 'POST' },
  '/kickMember':       { h: kickMember,       m: 'POST' },
  '/getBannedList':    { h: getBannedList,    m: 'GET' },
  '/unbanMember':      { h: unbanMember,      m: 'POST' },
  '/getLogs':          { h: getLogs,          m: 'GET' },
  '/addLog':           { h: addLog,           m: 'POST' },
};

exports.main = async (event, context) => {
  const httpMethod = event.httpMethod || event.method || 'GET';
  const path = event.path || event.url || '/';

  if (httpMethod === 'OPTIONS') {
    return {
      statusCode: 200,
      headers: { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type' },
      body: '',
    };
  }

  let relativePath = path;
  if (relativePath.startsWith('/boss-timer')) relativePath = relativePath.replace('/boss-timer', '');
  if (!relativePath) relativePath = '/';

  console.log(httpMethod, path, '->', relativePath);

  const route = ROUTES[relativePath];
  if (!route) return errResp('unknown route: ' + relativePath, 'known: ' + Object.keys(ROUTES).join(', '));
  if (route.m !== httpMethod) return errResp('method ' + httpMethod + ' not allowed', 'expected ' + route.m);

  let params = {};
  if (httpMethod === 'GET') {
    params = event.queryStringParameters || event.query || {};
  } else if (event.body) {
    try {
      params = typeof event.body === 'string' ? JSON.parse(event.body) : event.body;
    } catch (e) { return errResp('invalid JSON', e.message); }
  }

  try {
    return await route.h(params);
  } catch (e) {
    console.error('Error:', e);
    return errResp(e.message || 'internal error', String(e));
  }
};
