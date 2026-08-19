// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.UI
{
    class CombineButton : MonoBehaviour
    {
        [UnityEvent(wired: "CombineSystem/CombineButton")]
        // confidence verified
        void OnButtonClick()
        {
            if (CombineButton.combineZone.activeSelf == 0)
            {
                CombineButton.combineZone.SetActive(true);                 // IL_0014
            }

            if ((CombineButton.combineZone.activeSelf != 0) && (CombineButton.combineZone.activeSelf != 0))
            {
                CombineButton.combineZone.SetActive(false);                // IL_002E
            }
        }
    }
}
