package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class MotionWorkshopRig {
    record Joint(int parent, Vec3 pivot, Vec3 axis, double base, double swing, double period, double phase,
                 int color, Vec3[] vertices, int[][] faces) {}
    record Pose(Vec3 pivot, Quaterniond rotation) {
        Vec3 transform(Vec3 point) {
            var v = rotation.transform(new Vector3d(point.x, point.y, point.z));
            return pivot.add(v.x, v.y, v.z);
        }
    }
    private final List<Joint> joints = new ArrayList<>();

    MotionWorkshopRig(JsonObject item) {
        var entries = array(item.get("joints"), 1, 24, "rig.joints");
        var ids = new HashMap<String, Integer>();
        for (var entry : entries) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("Expected a rig joint");
            var obj = entry.getAsJsonObject();
            var o = new OrbEffectOptions(obj);
            String id = o.text("id", ""), parent = o.text("parent", "");
            if (id.isBlank() || ids.containsKey(id) || (!parent.isEmpty() && !ids.containsKey(parent)))
                throw new IllegalArgumentException("Joints need unique IDs and must follow their parent");
            var points = array(obj.get("vertices"), 3, 48, "joint.vertices");
            var vertices = new Vec3[points.size()];
            for (int i = 0; i < points.size(); i++) vertices[i] = vector(points.get(i));
            var polygons = array(obj.get("faces"), 1, 48, "joint.faces");
            int[][] faces = new int[polygons.size()][];
            for (int i = 0; i < polygons.size(); i++) {
                var indices = array(polygons.get(i), 3, 4, "joint face");
                faces[i] = new int[indices.size()];
                for (int j = 0; j < indices.size(); j++) {
                    double n = scalar(indices.get(j), vertices.length - 1);
                    if (n != (int)n || n < 0) throw new IllegalArgumentException("Invalid face index");
                    faces[i][j] = (int)n;
                }
            }
            Vec3 axis = vector(obj, "axis", Vec3.Y_AXIS);
            if (axis.lengthSqr() < 1e-6) throw new IllegalArgumentException("Joint axis must have a direction");
            joints.add(new Joint(ids.getOrDefault(parent, -1), vector(obj, "pivot", Vec3.ZERO), axis.normalize(),
                    Math.toRadians(o.number("baseDegrees", 0, -360, 360)),
                    Math.toRadians(o.number("swingDegrees", 0, -360, 360)), o.number("periodTicks", 16, 2, 600),
                    Math.toRadians(o.number("phaseDegrees", 0, -720, 720)), o.color("color", 0xff5ecfc7), vertices, faces));
            ids.put(id, joints.size() - 1);
        }
    }

    Pose[] poses(Vec3 center, Vec3 heading, double ticks) {
        double yaw = Math.atan2(heading.x, heading.z);
        double pitch = -Math.atan2(heading.y, Math.hypot(heading.x, heading.z));
        var root = new Pose(center, new Quaterniond().rotateY(yaw).rotateX(pitch));
        var poses = new Pose[joints.size()];
        for (int i = 0; i < joints.size(); i++) {
            var joint = joints.get(i);
            var parent = joint.parent < 0 ? root : poses[joint.parent];
            double angle = joint.base + joint.swing * Math.sin(ticks * Math.TAU / joint.period + joint.phase);
            Vec3 a = joint.axis;
            poses[i] = new Pose(parent.transform(joint.pivot), new Quaterniond(parent.rotation).rotateAxis(angle, a.x, a.y, a.z));
        }
        return poses;
    }

    void draw(EffectFrame out, Vec3 center, Vec3 heading, double ticks, double scale) {
        var poses = poses(Vec3.ZERO, heading, ticks);
        for (int i = 0; i < poses.length; i++) {
            var joint = joints.get(i);
            var vertices = new Vec3[joint.vertices.length];
            for (int n = 0; n < vertices.length; n++) vertices[n] = center.add(poses[i].transform(joint.vertices[n]).scale(scale));
            for (int[] face : joint.faces) {
                Vec3 a = vertices[face[0]], b = vertices[face[1]], c = vertices[face[2]];
                out.quad(a, b, c, vertices[face[face.length - 1]], joint.color);
            }
        }
    }

    static com.google.gson.JsonArray array(JsonElement v, int min, int max, String key) {
        if (v == null || !v.isJsonArray() || v.getAsJsonArray().size() < min || v.getAsJsonArray().size() > max)
            throw new IllegalArgumentException("Invalid array in " + key);
        return v.getAsJsonArray();
    }

    static double scalar(JsonElement value, double max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected a finite number");
        double n = value.getAsDouble();
        if (!Double.isFinite(n) || Math.abs(n) > max) throw new IllegalArgumentException("Value outside geometry bounds");
        return n;
    }

    static Vec3 vector(JsonObject o, String key, Vec3 fallback) {
        return o.has(key) ? vector(o.get(key)) : fallback;
    }

    private static Vec3 vector(JsonElement e) {
        var a = array(e, 3, 3, "point");
        return new Vec3(scalar(a.get(0), 8), scalar(a.get(1), 8), scalar(a.get(2), 8));
    }
}
