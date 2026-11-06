// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Scenes.Test
{
    class TrackingTest : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            StartCoroutine(CallActions());                             // IL_0002
        }

        [UnityLifecycle]
        // reached from: TrackingTest.Start
        // confidence derived
        IEnumerator CallActions()
        {
            TrackingTest.trackingInt += 1;                             // IL_0047
            TrackingTest.trackingString = String.Concat("string-", Int32.ToString()); // IL_0062
            TrackingTest.SuccessVoid();                                // IL_0068
            TrackingTest.SuccessInt();                                 // IL_006E

            TrackingTest.FailVoid();                                   // IL_0075
            goto IL_001E;                                              // loop
        }
    }
}
