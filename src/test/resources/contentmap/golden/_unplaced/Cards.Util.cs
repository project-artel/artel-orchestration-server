// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Cards
{
    class Util : MonoBehaviour
    {
        [UnityLifecycle]
        // reached from: CardManager.Update
        // confidence derived
        Vector3 MousePos { get; }
        {
            if ((CardManager.isMyCardDrag != 0) && (InteractionLock.IsLocked == 0))
            {
                Vector3.z = -10;                                           // IL_0017
            }
        }
    }
}
