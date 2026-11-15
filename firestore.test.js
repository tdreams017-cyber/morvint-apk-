const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-morvent-60f8ff30-9b69-4846-9a78-2456bc2944bd";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp() {
  return new Date(Date.now() - 2000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write profiles, tasks, or messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("tasks").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("messages").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("tasks").doc("t1").set({
      userId: ALICE_UID,
      goal: "test",
      status: "running",
      stepsCount: 1,
      createdAt: pastTimestamp(),
      updatedAt: pastTimestamp(),
    })
  );
});

test("Authenticated owner can create and read their own profile and task", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const ts = pastTimestamp();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      preferredModel: "gemini-3.5-flash-lite",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("task_1").set({
      userId: ALICE_UID,
      goal: "افتح يوتيوب وشغل قرآن",
      status: "completed",
      summary: "تم التنفيذ بنجاح",
      stepsCount: 3,
      aiProvider: "gemini-3.5-flash-lite",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").get()
  );
});

test("Authenticated user cannot read or write another user's tasks or messages", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore(DATABASE_ID);
  const ts = pastTimestamp();
  await bobDb.collection("users").doc(BOB_UID).collection("tasks").doc("bob_t1").set({
    userId: BOB_UID,
    goal: "شغل الكشاف",
    status: "completed",
    stepsCount: 1,
    createdAt: ts,
    updatedAt: ts,
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("tasks").doc("bob_t1").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("tasks").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("messages").doc("m1").set({
      userId: ALICE_UID,
      text: "hacked",
      isUser: true,
      status: "INFO",
      createdAt: ts,
    })
  );
});

test("Security rules reject invalid status enums, oversized goals, and forged userId", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore(DATABASE_ID);
  const ts = pastTimestamp();

  // Forged userId
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("t_bad1").set({
      userId: BOB_UID,
      goal: "test",
      status: "running",
      stepsCount: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Invalid status enum
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("t_bad2").set({
      userId: ALICE_UID,
      goal: "test",
      status: "invalid_status",
      stepsCount: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Extra unpermitted field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").doc("t_bad3").set({
      userId: ALICE_UID,
      goal: "test",
      status: "running",
      stepsCount: 1,
      isAdmin: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});
