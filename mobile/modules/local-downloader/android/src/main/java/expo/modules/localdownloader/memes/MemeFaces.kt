package expo.modules.localdownloader.memes

/**
 * Faces in memes: `shared/memes/CONTRACT.md`, "Faces". The rules for who a face is, the
 * same as the Mac's MemeFaces.swift. The arithmetic is shared/faces' C++ ([FaceMath]).
 */

/** The faces pipeline's arithmetic. On a device, [FacesNative]; in the JVM tests, a double. */
interface FaceMath {
  val sure: Float
  val ask: Float
  val pipelineVersion: Int

  fun cosine(a: FloatArray, b: FloatArray): Float

  /** The highest cosine against any of [set]; -1 for an empty set. */
  fun best(signature: FloatArray, set: List<FloatArray>): Float

  /** At most eight, spread out. */
  fun addToSet(set: List<FloatArray>, signature: FloatArray): List<FloatArray>

  /** A group number per signature, largest group first. */
  fun group(signatures: List<FloatArray>, threshold: Float): IntArray

  /** 128 half floats, base64. */
  fun encode(signature: FloatArray): String
  fun decode(text: String): FloatArray?
}

enum class FaceState(val wire: String) {
  /** Sure enough to label on its own. */
  AUTO("auto"),
  /** Named or confirmed by the user. */
  CONFIRMED("confirmed"),
  /** Close to its person, waiting for a yes or no. */
  ASKED("asked"),
  /** Nobody known. */
  UNNAMED("unnamed");

  companion object {
    fun of(wire: String) = values().firstOrNull { it.wire == wire } ?: UNNAMED
  }
}

/** One person's face in one meme. */
data class Face(
  val id: String,
  /** 128 half floats, base64. */
  val signature: String,
  /** Where to show it from: the video time, and the box in the frame's pixels. */
  val frameMs: Int,
  val box: List<Double>,
  val person: String? = null,
  val state: FaceState = FaceState.UNNAMED,
  /** People this face has been said not to be. */
  val rejected: List<String> = emptyList(),
  /** Whether this face put its person's label on the meme, and so may take it off. */
  val added: Boolean = false,
)

/** A face as a scan found it, before it is matched against anyone. */
class ScannedFace(val signature: FloatArray, val box: List<Double>, val frameMs: Int)

/**
 * The rules, over the index's lists. Each changes items and people in place; MemeStore calls
 * them inside one write.
 */
internal class FaceRules(private val math: FaceMath) {

  fun signatureSets(people: List<MemeStore.Person>): Map<String, List<FloatArray>> =
    people.mapNotNull { person ->
      val set = person.signatures.mapNotNull { math.decode(it) }
      if (set.isEmpty()) null else person.id to set
    }.toMap()

  /** Sure: auto. Close: asked. Otherwise unnamed. Never someone it was said not to be. */
  fun classify(face: Face, signature: FloatArray, people: Map<String, List<FloatArray>>): Face {
    var bestId: String? = null
    var bestScore = -2f
    for ((id, set) in people) {
      if (id in face.rejected) continue
      val score = math.best(signature, set)
      // Ties by id, so both apps pick the same person from the same state.
      if (bestId == null || score > bestScore || (score == bestScore && id < bestId)) {
        bestId = id
        bestScore = score
      }
    }
    return when {
      bestId != null && bestScore >= math.sure -> face.copy(person = bestId, state = FaceState.AUTO)
      bestId != null && bestScore >= math.ask -> face.copy(person = bestId, state = FaceState.ASKED)
      else -> face.copy(person = null, state = FaceState.UNNAMED)
    }
  }

  /** Records a scan's faces on an item, matched against the people known. */
  fun record(item: MemeStore.Item, scanned: List<ScannedFace>, people: List<MemeStore.Person>): MemeStore.Item {
    val sets = signatureSets(people)
    val faces = scanned.map { scan ->
      classify(Face(MemeStore.newId("f"), math.encode(scan.signature), scan.frameMs, scan.box), scan.signature, sets)
    }
    return applyAutomatic(item.copy(faces = faces, facesVersion = math.pipelineVersion))
  }

  /**
   * Every face not already settled by the user, looked at again against the people now known.
   * Automatic labels that no longer hold are taken back.
   */
  fun reevaluate(items: MutableList<MemeStore.Item>, people: List<MemeStore.Person>) {
    val sets = signatureSets(people)
    for (i in items.indices) {
      val item = items[i]
      if (item.faces.isEmpty()) continue
      val faces = item.faces.toMutableList()
      var labels = item.people
      for (f in faces.indices) {
        val before = faces[f]
        if (before.state == FaceState.CONFIRMED) continue
        val signature = math.decode(before.signature) ?: continue
        var after = classify(before, signature, sets)
        if (before.state == FaceState.AUTO && before.added && (after.person != before.person || after.state != FaceState.AUTO)) {
          after = after.copy(added = false)
          val stillShown = faces.withIndex().any { (j, other) ->
            j != f && other.person == before.person && (other.state == FaceState.AUTO || other.state == FaceState.CONFIRMED)
          }
          if (!stillShown) labels = labels - before.person!!
        } else if (after.state == FaceState.AUTO && after.person == before.person) {
          after = after.copy(added = before.added)
        }
        faces[f] = after
      }
      items[i] = applyAutomatic(item.copy(faces = faces, people = labels))
    }
  }

  /** Puts the person of every automatic face on the meme, remembering which it added. */
  fun applyAutomatic(item: MemeStore.Item): MemeStore.Item {
    var result = item
    for (f in item.faces.indices) {
      val face = result.faces[f]
      if (face.state == FaceState.AUTO && face.person != null) result = labelFrom(result, f, face.person)
    }
    return result
  }

  fun labelFrom(item: MemeStore.Item, f: Int, person: String): MemeStore.Item {
    if (person in item.people) return item
    val faces = item.faces.toMutableList()
    faces[f] = faces[f].copy(added = true)
    return item.copy(people = item.people + person, faces = faces)
  }

  /**
   * Not this person: remembered, and the label goes if this face put it there and no other
   * face of theirs holds it.
   */
  fun unlabel(item: MemeStore.Item, f: Int): MemeStore.Item {
    val face = item.faces[f]
    val person = face.person ?: return item
    val faces = item.faces.toMutableList()
    faces[f] = face.copy(rejected = (face.rejected + person).distinct().sorted(), person = null,
      state = FaceState.UNNAMED, added = false)
    val stillShown = faces.any { it.person == person && (it.state == FaceState.AUTO || it.state == FaceState.CONFIRMED) }
    val people = if (face.added && !stillShown) item.people - person else item.people
    return item.copy(faces = faces, people = people)
  }

  /** A label taken off by hand is a "no" to every face that gave it. */
  fun rejectFaces(item: MemeStore.Item, removed: Set<String>): MemeStore.Item {
    if (item.faces.none { it.person in removed && it.state != FaceState.UNNAMED }) return item
    return item.copy(faces = item.faces.map { face ->
      val person = face.person
      if (person != null && person in removed && face.state != FaceState.UNNAMED) {
        face.copy(rejected = (face.rejected + person).distinct().sorted(), person = null,
          state = FaceState.UNNAMED, added = false)
      } else {
        face
      }
    })
  }

  /** Adds a signature to a person's set, unless it is one they already have. */
  fun learn(signature: String, personId: String, people: MutableList<MemeStore.Person>) {
    val decoded = math.decode(signature) ?: return
    val at = people.indexOfFirst { it.id == personId }
    if (at < 0) return
    val set = people[at].signatures.mapNotNull { math.decode(it) }
    // The same signature twice would crowd the set without teaching it anything.
    if (set.any { math.cosine(it, decoded) > 0.999f }) return
    people[at] = people[at].copy(signatures = math.addToSet(set, decoded).map { math.encode(it) })
  }

  /** Unnamed faces in groups of the same person, largest first, as (item, face) pairs. */
  fun unnamedGroups(items: List<MemeStore.Item>): List<List<Pair<MemeStore.Item, Face>>> {
    val refs = items.flatMap { item -> item.faces.filter { it.state == FaceState.UNNAMED }.map { item to it } }
    if (refs.isEmpty()) return emptyList()
    val signatures = refs.map { math.decode(it.second.signature) ?: FloatArray(128) }
    val labels = math.group(signatures, math.sure)
    val groups = List((labels.maxOrNull() ?: -1) + 1) { mutableListOf<Pair<MemeStore.Item, Face>>() }
    refs.forEachIndexed { i, ref -> groups[labels[i]].add(ref) }
    return groups.filter { it.isNotEmpty() }
  }
}
