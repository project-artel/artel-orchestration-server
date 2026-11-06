// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class SelectableObject : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void OnMouseExit()
        {
            ChangeSize(false);                                         // IL_0002
        }

        [UnityLifecycle]
        // reached from: SelectableObject.OnMouseExit, SelectableObject.OnMouseEnter
        // confidence derived/partial; gaps: callee-condition-not-composed
        void ChangeSize(bool p0)
        {
            if (bigSide != 0)
            {
                Component.transform.localScale = Vector3.op_Multiply(SelectableObject.scale, 1.2); // IL_0019
            }

            if (bigSide == 0)
            {
                Component.transform.localScale = SelectableObject.scale;   // IL_002B
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnMouseDown()
        {
            if (SelectableObject.selectable != 0)
            {
                InteractionLock.IsLocked;                                  // IL_0008
            }

            if ((InteractionLock.IsLocked == 0) && (SelectableObject.selectable != 0))
            {
                SelectableObject.combineZone.SetTarget();                  // IL_0016
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnMouseEnter()
        {
            if (SelectableObject.selectable != 0)
            {
                InteractionLock.IsLocked;                                  // IL_0008
            }

            if ((InteractionLock.IsLocked == 0) && (SelectableObject.selectable != 0))
            {
                ChangeSize(true);                                          // IL_0011
            }
        }

        [InspectorCallable]
        // called by: Combat.UI.CombineZone.SetAllSelectable
        // confidence verified
        void SetSelectable(bool p0)
        {
            SelectableObject.selectable = selectable;                  // IL_0002
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            SelectableObject.scale = this.transform.localScale;        // IL_000C
            SelectableObject.combineZone = CombineZone.Instance;       // IL_0017
        }
    }
}
