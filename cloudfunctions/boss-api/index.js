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
  if (countRes.total >= 10) return errResp('max_rooms_reached');

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

  const updateData = {};
  if (role) updateData.role = role;
  if (permissions) updateData.permissions = permissions;

  await db.collection(MEMBERS).where({ roomId, userId: targetUserId }).update(updateData);
  await bumpVersion(roomId);

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
    lastModifier: userId,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  });

  await bumpVersion(roomId);
  const room = await getRoom(roomId);

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

  await db.collection(BOSSES).doc(docId).update(up);
  await bumpVersion(roomId);
  const room = await getRoom(roomId);

  return jsonResp({ version: room.version });
}

async function deleteBoss(params) {
  const { roomId, userId, docId } = params;
  if (!roomId || !userId || !docId) return errResp('roomId, userId, docId are required');

  const member = await getMember(roomId, userId);
  if (!member) return errResp('not a member');
  if (!canDo(member, 'canDelete') && member.role !== 'owner')
    return errResp('no permission to delete boss');

  await db.collection(BOSSES).doc(docId).remove();
  await bumpVersion(roomId);
  const room = await getRoom(roomId);

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

  const res = await db.collection(ROOMS).where({ ownerUserId: userId }).get();
  const rooms = res.data.map(r => ({
    roomId: r.roomId,
    roomName: r.roomName,
    hasPassword: !!r.password,
    version: r.version,
    createdAt: r.createdAt,
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

  const room = await getRoom(roomId);
  if (!room) return errResp('room not found');
  if (room.ownerUserId !== ownerUserId) return errResp('only owner can kick');

  await db.collection(MEMBERS).where({ roomId, userId: targetUserId }).remove();
  await bumpVersion(roomId);
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
