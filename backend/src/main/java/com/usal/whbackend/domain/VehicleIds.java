package com.usal.whbackend.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Maps the vehicle ids Infra uses (e.g. {@code VHC-001}) to the UUIDs the rest of the backend
 * knows. The mapping is a UUIDv5, so it is deterministic: the same Infra id always yields the same
 * UUID, and nothing has to be registered or stored to translate between the two.
 */
public final class VehicleIds {

  // Part of every derived id: changing it renames every vehicle, so it must never change.
  private static final UUID INFRA_NAMESPACE =
      UUID.fromString("6f0a5d3e-2b7c-4c1e-9a58-3d1f7b2e4a90");

  private VehicleIds() {}

  public static String fromInfra(String infraId) {
    return uuidV5(INFRA_NAMESPACE, infraId.trim()).toString();
  }

  static UUID uuidV5(UUID namespace, String name) {
    byte[] hash;
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(
          ByteBuffer.allocate(16)
              .putLong(namespace.getMostSignificantBits())
              .putLong(namespace.getLeastSignificantBits())
              .array());
      hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is required by every JVM", e);
    }
    hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
    hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
    ByteBuffer bytes = ByteBuffer.wrap(hash);
    return new UUID(bytes.getLong(), bytes.getLong());
  }
}
