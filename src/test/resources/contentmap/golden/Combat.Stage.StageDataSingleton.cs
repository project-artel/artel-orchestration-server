// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Stage
{
    class StageDataSingleton : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence partial/verified; gaps: singleton-plumbing
        void Awake()
        {
            StageDataSingleton.Instance;                               // IL_0000

            if (StageDataSingleton.Instance == null)
            {
                StageDataSingleton.Instance = /* ? */;                     // IL_000E
                StageDataSingleton.Instance = /* ? */;                     // IL_000E
            }

            if (StageDataSingleton.Instance != null)
            {
                Destroy(this.gameObject);                                  // IL_0025
            }
        }

        [UnityLifecycle]
        // reached from: StageDataSingleton.Awake
        // confidence derived
        StageDataSingleton Instance { set; }
        {
            if (StageDataSingleton.Instance == null)
            {
                StageDataSingleton.Instance = value;                       // IL_0001
            }
        }
    }
}
